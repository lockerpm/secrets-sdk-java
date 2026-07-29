package locker;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class VersionConsistencyTest {
    @Test
    public void keepsReleaseMetadataAligned() throws Exception {
        DocumentBuilderFactory factory =
                DocumentBuilderFactory.newInstance();
        factory.setFeature(
                "http://apache.org/xml/features/disallow-doctype-decl",
                true
        );
        factory.setFeature(
                "http://xml.org/sax/features/external-general-entities",
                false
        );
        factory.setFeature(
                "http://xml.org/sax/features/external-parameter-entities",
                false
        );
        factory.setAttribute(
                XMLConstants.ACCESS_EXTERNAL_DTD,
                ""
        );
        factory.setAttribute(
                XMLConstants.ACCESS_EXTERNAL_SCHEMA,
                ""
        );
        Document pom = factory.newDocumentBuilder().parse(
                Path.of("pom.xml").toFile()
        );
        String pomVersion = (String) XPathFactory
                .newInstance()
                .newXPath()
                .evaluate(
                        "/*[local-name()='project']"
                                + "/*[local-name()='version']/text()",
                        pom,
                        XPathConstants.STRING
                );
        String defaultRevision = (String) XPathFactory
                .newInstance()
                .newXPath()
                .evaluate(
                        "/*[local-name()='project']"
                                + "/*[local-name()='properties']"
                                + "/*[local-name()='revision']/text()",
                        pom,
                        XPathConstants.STRING
                );
        String buildVersion = System.getProperty(
                "locker.sdk.version"
        );

        assertEquals("${revision}", pomVersion.trim());
        assertTrue(defaultRevision.matches(
                "^(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)"
                        + "\\.(0|[1-9][0-9]*)$"
        ));
        assertEquals(buildVersion, LockerConfiguration.SDK_VERSION);
    }
}
