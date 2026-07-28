package locker;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class VersionConsistencyTest {
    @Test
    public void keepsReleaseMetadataAligned() throws Exception {
        String releaseVersion = Files.readString(
                Path.of("VERSION"),
                StandardCharsets.UTF_8
        ).trim();

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

        assertEquals(releaseVersion, pomVersion.trim());
        assertEquals(releaseVersion, LockerConfiguration.SDK_VERSION);
    }
}
