package fr.ftoumhub.poccr.infrastructure.editeur;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Produit un .docx minimal valide (3 parties OPC), sans dépendance à POI ni docx4j. */
final class DocumentVierge {

    private static final String CONTENT_TYPES = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
              <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
              <Default Extension="xml" ContentType="application/xml"/>
              <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
            </Types>""";

    private static final String RELS = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
              <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
            </Relationships>""";

    private static final String DOCUMENT = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
              <w:body>
                <w:p><w:r><w:t>Compte rendu</w:t></w:r></w:p>
                <w:p/>
              </w:body>
            </w:document>""";

    private DocumentVierge() {
    }

    static byte[] octets() {
        try (ByteArrayOutputStream sortie = new ByteArrayOutputStream();
             ZipOutputStream zip = new ZipOutputStream(sortie)) {
            ajouter(zip, "[Content_Types].xml", CONTENT_TYPES);
            ajouter(zip, "_rels/.rels", RELS);
            ajouter(zip, "word/document.xml", DOCUMENT);
            zip.finish();
            return sortie.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void ajouter(ZipOutputStream zip, String nom, String contenu) throws IOException {
        zip.putNextEntry(new ZipEntry(nom));
        zip.write(contenu.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
