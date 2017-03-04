package gov.cms.qpp.conversion.validate;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.collection.IsEmptyCollection.empty;
import static org.hamcrest.collection.IsIterableWithSize.iterableWithSize;
import static org.junit.Assert.assertThat;

import java.util.List;

import org.junit.Before;
import org.junit.Test;

import gov.cms.qpp.conversion.model.Node;
import gov.cms.qpp.conversion.model.ValidationError;

public class ClinicalDocumentValidatorTest {

	private static final String EXPECTED_TEXT = "Clinical Document Node is required";

	@Before
	public void setup() {
		ClinicalDocumentValidator.resetValidationErrors();
	}

	@Test
	public void testClinicalDocumentPresent() {

		Node clinicalDocumentNode = new Node("2.16.840.1.113883.10.20.27.1.2");
		clinicalDocumentNode.putValue("programName", "mips");
		clinicalDocumentNode.putValue("taxpayerIdentificationNumber", "123456789");
		clinicalDocumentNode.putValue("nationalProviderIdentifier", "2567891421");
		clinicalDocumentNode.putValue("performanceStart", "20170101");
		clinicalDocumentNode.putValue("performanceEnd", "20171231");

		Node aciSectionNode = new Node(clinicalDocumentNode, "2.16.840.1.113883.10.20.27.2.5");
		aciSectionNode.putValue("category", "aci");

		clinicalDocumentNode.addChildNode(aciSectionNode);

		ClinicalDocumentValidator cdval = new ClinicalDocumentValidator();
		List<ValidationError> errors = cdval.internalValidate(clinicalDocumentNode);

		assertThat("no errors should be present", errors, empty());

	}

	@Test
	public void testClinicalDocumentNotPresent() {

		Node aciSectionNode = new Node();
		aciSectionNode.setId("2.16.840.1.113883.10.20.27.2.5");
		aciSectionNode.putValue("category", "aci");

		ClinicalDocumentValidator cdval = new ClinicalDocumentValidator();
		List<ValidationError> errors = cdval.internalValidate(aciSectionNode);

		assertThat("there should be one error", errors, iterableWithSize(1));
		assertThat("error should be about missing Clinical Document node", errors.get(0).getErrorText(),
				is(EXPECTED_TEXT));

	}

}
