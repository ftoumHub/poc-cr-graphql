package fr.ftoumhub.poccr.domaine.validation;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Validation en train d'être produite : la requête est exécutée avant que la validation n'existe,
 * mais les champs {@code document.validation.*} insérés dans le compte rendu doivent être figés
 * avec la date et le valideur définitifs, pas à null.
 */
public record ValidationEnCours(UUID documentId, Instant valideLe, UUID valideurId) {

    public ValidationEnCours {
        Objects.requireNonNull(documentId, "documentId");
        Objects.requireNonNull(valideLe, "valideLe");
        Objects.requireNonNull(valideurId, "valideurId");
    }
}
