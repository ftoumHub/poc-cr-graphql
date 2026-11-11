package fr.ftoumhub.poccr.domaine.professionnel;

import java.util.Objects;
import java.util.UUID;

/** Numéro rattaché à une adresse ; au plus un numéro par type et par adresse. */
public record Telephone(UUID id, UUID adresseId, TypeTelephone type, String numero) {

    public Telephone {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(adresseId, "adresseId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(numero, "numero");
    }
}
