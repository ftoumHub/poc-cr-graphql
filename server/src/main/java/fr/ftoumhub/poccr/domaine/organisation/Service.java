package fr.ftoumhub.poccr.domaine.organisation;

import java.util.Objects;
import java.util.UUID;

/** Service d'un établissement ; regroupe des unités fonctionnelles. */
public record Service(String code, UUID etablissementId, String libelle) {

    public Service {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(etablissementId, "etablissementId");
        Objects.requireNonNull(libelle, "libelle");
    }
}
