package fr.ftoumhub.poccr.domaine.dossier;

import java.util.Optional;

/**
 * Sélection de formulaires.
 *
 * @param modele    code du modèle ; null = tous
 * @param contenant code d'une rubrique du modèle recherché : désigne le modèle quand son identifiant n'est
 *                  pas connu (contextes de l'application d'origine) ; null = sans condition
 */
public record FiltreFormulaires(String modele, String contenant) {

    public Optional<String> modeleAttendu() {
        return Optional.ofNullable(modele).filter(m -> !m.isBlank());
    }

    public Optional<String> rubriqueAttendue() {
        return Optional.ofNullable(contenant).filter(c -> !c.isBlank());
    }
}
