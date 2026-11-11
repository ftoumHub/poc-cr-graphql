package fr.ftoumhub.poccr.domaine.validation;

import java.util.Objects;
import java.util.UUID;

/**
 * Variables de la requête DonneesDocument, fixées par le serveur à la validation et jamais reprises
 * du client : {@code $doc} est le document validé, {@code $id} le patient rattaché à ce document.
 */
public record VariablesDocument(UUID documentId, UUID patientId) {

    public VariablesDocument {
        Objects.requireNonNull(documentId, "documentId");
        Objects.requireNonNull(patientId, "patientId");
    }
}
