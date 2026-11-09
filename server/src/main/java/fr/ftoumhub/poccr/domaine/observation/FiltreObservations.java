package fr.ftoumhub.poccr.domaine.observation;

import java.time.LocalDate;
import java.util.Optional;

/**
 * Critères appliqués à la liste des observations d'un patient.
 * Les composants absents (null) signifient « pas de filtre ».
 * Record = égalité structurelle, ce qui permet de grouper les patients par filtre identique
 * et de ne faire qu'une requête SQL par filtre distinct.
 */
public record FiltreObservations(CategorieObservation categorie, String code, LocalDate depuis) {

    public static final FiltreObservations AUCUN = new FiltreObservations(null, null, null);

    public Optional<CategorieObservation> categorieAttendue() {
        return Optional.ofNullable(categorie);
    }

    public Optional<String> codeAttendu() {
        return Optional.ofNullable(code).filter(c -> !c.isBlank());
    }

    public Optional<LocalDate> depuisLe() {
        return Optional.ofNullable(depuis);
    }
}
