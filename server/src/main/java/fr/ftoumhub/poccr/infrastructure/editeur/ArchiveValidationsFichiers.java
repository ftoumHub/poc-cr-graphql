package fr.ftoumhub.poccr.infrastructure.editeur;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import fr.ftoumhub.poccr.domaine.port.ArchiveValidations;
import fr.ftoumhub.poccr.domaine.validation.NomDocument;
import fr.ftoumhub.poccr.domaine.validation.Validation;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

/**
 * Stocke chaque validation dans {@code <nom>.validation.json}, à côté du docx.
 * Pour la suite : table PostgreSQL ou stockage objet avec verrou WORM.
 */
public class ArchiveValidationsFichiers implements ArchiveValidations {

    private static final String SUFFIXE = ".validation.json";

    private final Path dossier;
    private final ObjectMapper json;

    public ArchiveValidationsFichiers(Path dossier, ObjectMapper objectMapper) {
        this.dossier = dossier;
        this.json = objectMapper.copy()
                .configure(SerializationFeature.INDENT_OUTPUT, true)
                .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
        try {
            Files.createDirectories(dossier);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public synchronized Optional<Validation> deDocument(NomDocument document) {
        Path fichier = fichier(document.valeur());
        if (!Files.exists(fichier)) {
            return Optional.empty();
        }
        try {
            return Optional.of(json.readValue(fichier.toFile(), Validation.class));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public synchronized void enregistrer(Validation validation) {
        Path fichier = fichier(new NomDocument(validation.document()).valeur());
        try {
            Path temporaire = Files.createTempFile(dossier, validation.document(), ".tmp");
            json.writeValue(temporaire.toFile(), validation);
            Files.move(temporaire, fichier, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Path fichier(String nom) {
        return dossier.resolve(nom + SUFFIXE);
    }
}
