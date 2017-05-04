package gov.cms.qpp.conversion.decode;

import gov.cms.qpp.BaseTest;
import gov.cms.qpp.conversion.model.Node;
import gov.cms.qpp.conversion.model.TemplateId;
import gov.cms.qpp.conversion.xml.XmlException;
import gov.cms.qpp.conversion.xml.XmlUtils;
import org.jdom2.Element;
import org.jdom2.Namespace;
import org.junit.Test;

import java.io.IOException;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.core.IsNot.not;
import static org.hamcrest.core.IsNull.nullValue;
import static org.junit.Assert.assertThat;

public class AciMeasurePerformedRnRDecoderTest extends BaseTest {
	private static final String MEASURE_ID = "ACI_INFBLO_1";

	@Test
	public void internalDecodeReturnsTreeContinue() {
		//set-up
		AciMeasurePerformedRnRDecoder objectUnderTest = new AciMeasurePerformedRnRDecoder();
		
		Namespace rootns = Namespace.getNamespace("urn:hl7-org:v3");
		Namespace ns = Namespace.getNamespace("xsi", "http://www.w3.org/2001/XMLSchema-instance");

		Element element = new Element("organizer", rootns);
		Element templateIdElement = new Element("templateId", rootns)
			                            .setAttribute("root","2.16.840.1.113883.10.20.27.3.28");
		Element referenceElement = new Element("reference", rootns);
		Element externalDocumentElement = new Element("externalDocument", rootns);
		Element idElement = new Element("id", rootns).setAttribute("extension", MEASURE_ID);

		externalDocumentElement.addContent(idElement);
		referenceElement.addContent(externalDocumentElement);
		element.addContent(templateIdElement);
		element.addContent(referenceElement);
		element.addNamespaceDeclaration(ns);

		Node aciMeasurePerformedNode = new Node();

		objectUnderTest.setNamespace(element, objectUnderTest);

		//execute
		DecodeResult decodeResult = objectUnderTest.internalDecode(element, aciMeasurePerformedNode);

		//assert
		assertThat("The decode result is incorrect.", decodeResult, is(DecodeResult.TREE_CONTINUE));
		String actualMeasureId = aciMeasurePerformedNode.getValue("measureId");
		assertThat("measureId must not be null.", actualMeasureId, is(not(nullValue())));
		assertThat("measureId is incorrect.", actualMeasureId, is(MEASURE_ID));
	}

	@Test
	public void testUpperLevel() throws XmlException, IOException {
		String needsFormattingXml = getFixture("AciMeasurePerformedIsolated.xml");
		String xml = String.format(needsFormattingXml, MEASURE_ID);
		Node wrapperNode = new QppXmlDecoder().decode(XmlUtils.stringToDom(xml));
		Node aciMeasurePerformedNode = wrapperNode.getChildNodes().get(0);

		String actualMeasureId = aciMeasurePerformedNode.getValue("measureId");

		assertThat("The measureId must not be null.", actualMeasureId, is(not(nullValue())));
		assertThat("The measureId is incorrect.", actualMeasureId, is(MEASURE_ID));
		long measurePerformedCount = aciMeasurePerformedNode.getChildNodes(
			node -> node.getId().equals(TemplateId.MEASURE_PERFORMED.getTemplateId())).count();
		assertThat("There must be one Measure Performed child node.", measurePerformedCount, is(1L));
	}
}