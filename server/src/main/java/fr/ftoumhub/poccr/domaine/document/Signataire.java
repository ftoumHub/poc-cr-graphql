package fr.ftoumhub.poccr.domaine.document;

import java.util.Objects;
import java.util.UUID;

/** Professionnel signataire d'un document ; le rang 1 est le signataire principal. */
public record Signataire(UUID professionnelId, int rang) {

    public Signataire {
        Objects.requireNonNull(professionnelId, "professionnelId");
        if (rang < 1) {
            throw new ActeursInvalides("Le rang d'un signataire commence à 1");
        }
    }

    public boolean estPrincipal() {
        return rang == 1;
    }
}
