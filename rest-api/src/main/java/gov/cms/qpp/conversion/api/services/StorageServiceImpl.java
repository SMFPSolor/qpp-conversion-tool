package gov.cms.qpp.conversion.api.services;

import java.io.InputStream;
import java.net.URI;
import java.security.KeyManagementException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import org.apache.commons.lang3.StringUtils;
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.ssl.TrustStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.RequestEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.GetObjectRequest;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.PutObjectRequest;
import com.amazonaws.services.s3.model.S3Object;
import com.amazonaws.services.s3.model.SSEAwsKeyManagementParams;
import com.amazonaws.services.s3.transfer.TransferManager;
import com.amazonaws.services.s3.transfer.Upload;

import gov.cms.qpp.conversion.api.config.CustomSimpleClientHttpRequestFactory;
import gov.cms.qpp.conversion.api.exceptions.UncheckedInterruptedException;
import gov.cms.qpp.conversion.api.model.Constants;
import gov.cms.qpp.conversion.api.model.NullHostnameVerifier;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;

/**
 * Used to store an {@link InputStream} in S3.
 */
@Service
public class StorageServiceImpl extends AnyOrderActionService<Supplier<PutObjectRequest>, String>
		implements StorageService {

	private static final Logger API_LOG = LoggerFactory.getLogger(StorageServiceImpl.class);
	static final String CONTENT_TYPE = "application/json";

	private final TransferManager s3TransferManager;
	private final Environment environment;
	private final AmazonS3 amazonS3;

	public StorageServiceImpl(TaskExecutor taskExecutor, TransferManager s3TransferManager,
			Environment environment, AmazonS3 amazonS3) {
		super(taskExecutor);

		Objects.requireNonNull(s3TransferManager, "s3TransferManager");
		Objects.requireNonNull(environment, "environment");
		Objects.requireNonNull(amazonS3, "amazonS3");

		this.s3TransferManager = s3TransferManager;
		this.environment = environment;
		this.amazonS3 = amazonS3;
	}

	/**
	 * Stores the {@link InputStream} as an object in the S3 bucket.
	 *
	 * @param keyName The requested key name for the object.
	 * @param inStream The {@link InputStream} to write out to an object in S3.
	 * @param size The size of the {@link InputStream}.
	 * @return A {@link CompletableFuture} that will eventually contain the S3 object key.
	 */
	@Override
	public CompletableFuture<String> store(String keyName, Supplier<InputStream> inStream, long size) {
		String bucketName = environment.getProperty(Constants.BUCKET_NAME_ENV_VARIABLE);
		String kmsKey = environment.getProperty(Constants.KMS_KEY_ENV_VARIABLE);
		if (StringUtils.isEmpty(bucketName) || StringUtils.isEmpty(kmsKey)) {
			API_LOG.warn("No bucket name is specified or no KMS key specified.");
			return CompletableFuture.completedFuture("");
		}

		ObjectMetadata s3ObjectMetadata = new ObjectMetadata();
		s3ObjectMetadata.setContentLength(size);

		Supplier<PutObjectRequest> putObjectRequest = () -> new PutObjectRequest(bucketName, keyName, inStream.get(), s3ObjectMetadata)
			.withSSEAwsKeyManagementParams(new SSEAwsKeyManagementParams(kmsKey));

		API_LOG.info("Writing object {} to S3 bucket {}", keyName, bucketName);
		return actOnItem(putObjectRequest);
	}

	/**
	 * Performs a {@link GetObjectRequest} to the S3 bucket by file id for the file
	 *
	 * @param fileLocationId Id of the file to search for
	 * @return file found from S3
	 */
	@Override
	public InputStream getFileByLocationId(String fileLocationId) {
		String bucketName = environment.getProperty(Constants.BUCKET_NAME_ENV_VARIABLE);
		if (StringUtils.isEmpty(bucketName)) {
			API_LOG.warn("No bucket name is specified.");
			return null;
		}

		API_LOG.info("Retrieving file {} from bucket {}", fileLocationId, bucketName);

		GetObjectRequest getObjectRequest = new GetObjectRequest(bucketName, fileLocationId);

		S3Object s3Object = amazonS3.getObject(getObjectRequest);

		API_LOG.info("Successfully retrieved file {} from S3 bucket {}", getObjectRequest.getKey(), getObjectRequest.getBucketName());

		return s3Object.getObjectContent();
	}

	/**
	 * Opens a stream to s3 to retrieve the CPC+ API to NPI Validation file
	 *
	 * @return file used for cpc+ validation.
	 */
	@Override
	public byte[] getCpcPlusValidationFile() {
		String fmsToken = environment.getProperty(Constants.FMS_TOKEN_ENV_VARIABLE);
		String arUrl = environment.getProperty(Constants.AR_API_URL_ENV_VARIABLE);
		if (StringUtils.isEmpty(fmsToken) || StringUtils.isEmpty(arUrl)) {
			API_LOG.warn("No token or url specified for file retrieval. Not retrieving CPC+ validation file");
			return new byte[0];
		}
		API_LOG.info("Retrieving CPC+ validation file from FMS");

		RestTemplate retrievePresignedUrlRest = new RestTemplate();
		setRestTemplate(retrievePresignedUrlRest);
		
		ResponseEntity<String> response = retrievePresignedUrlRest.exchange(arUrl, HttpMethod.GET,
			new HttpEntity<>("parameters", getHeaders(fmsToken)), String.class);
		String s3PresignedUrl = response.getBody();

		if (StringUtils.isNotEmpty(s3PresignedUrl)) {
			RestTemplate retrieveFileRest = new RestTemplate();
			RequestEntity<?> fileEntity = RequestEntity.get(URI.create(s3PresignedUrl)).build();
			ResponseEntity<byte[]> fileResponse = retrieveFileRest.exchange(fileEntity, byte[].class);
			return fileResponse.getBody();
		}

		API_LOG.error(arUrl + " was empty, missing, or did not return a response body. "
				+ "HTTP Status was " + response.getStatusCodeValue());
		return new byte[0];
	}

	private void setRestTemplate(RestTemplate template) {
		TrustStrategy acceptingTrustStrategy = (x509Certificates, s) -> true;
		try {
			SSLContext sslContext =
				org.apache.http.ssl.SSLContexts.custom().loadTrustMaterial(null, acceptingTrustStrategy).build();
			SSLConnectionSocketFactory csf = new SSLConnectionSocketFactory(sslContext, new NoopHostnameVerifier());
			CloseableHttpClient httpClient = HttpClients.custom().setSSLSocketFactory(csf).build();
			HttpComponentsClientHttpRequestFactory requestFactory = new HttpComponentsClientHttpRequestFactory();
			requestFactory.setHttpClient(httpClient);
			template.setRequestFactory(requestFactory);
		} catch (KeyStoreException | NoSuchAlgorithmException | KeyManagementException exc) {
			API_LOG.error("Unable to update certificate settings...");
		}
	}

	HttpHeaders getHeaders(String fmsToken) {
		HttpHeaders headers = new HttpHeaders();
		headers.add(HttpHeaders.CONTENT_TYPE, CONTENT_TYPE);
		headers.add(HttpHeaders.ACCEPT, CONTENT_TYPE);

		if (fmsToken != null && !fmsToken.isEmpty()) {
			headers.add(HttpHeaders.AUTHORIZATION,
				"Bearer " + fmsToken);
		}

		return headers;
	}

	/**
	 * Uses the {@link TransferManager} to upload a file.
	 *
	 * @param objectToActOn The put request.
	 * @return The object key in the bucket.
	 */
	@Override
	protected String asynchronousAction(Supplier<PutObjectRequest> objectToActOn) {
		String returnValue;

		PutObjectRequest request = objectToActOn.get();
		try {
			Upload upload = s3TransferManager.upload(request);
			returnValue = upload.waitForUploadResult().getKey();
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new UncheckedInterruptedException(exception);
		}

		API_LOG.info("Successfully wrote object {} to S3 bucket {}", returnValue, request.getBucketName());

		return returnValue;
	}
}