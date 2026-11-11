package fr.ftoumhub.poccr.domaine.professionnel;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Adresses d'un propriétaire (professionnel ou établissement) et leurs téléphones. */
public record Coordonnees(List<Adresse> adresses, List<Telephone> telephones) {

    public static final Coordonnees AUCUNE = new Coordonnees(List.of(), List.of());

    public Coordonnees {
        adresses = List.copyOf(adresses);
        telephones = List.copyOf(telephones);
    }

    public Optional<Adresse> adresse(TypeAdresse type) {
        return adresses.stream().filter(a -> a.type() == type).findFirst();
    }

    public Optional<Telephone> telephone(UUID adresseId, TypeTelephone type) {
        return telephones.stream().filter(t -> t.adresseId().equals(adresseId) && t.type() == type).findFirst();
    }

    /** Téléphone d'un type donné, sur l'adresse d'un type donné. */
    public Optional<Telephone> telephone(TypeAdresse typeAdresse, TypeTelephone type) {
        return adresse(typeAdresse).flatMap(a -> telephone(a.id(), type));
    }
}
