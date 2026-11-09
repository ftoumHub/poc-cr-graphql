package fr.ftoumhub.poccr.domaine.observation;

import java.time.LocalDate;
import java.time.Period;
import java.time.format.DateTimeParseException;
import java.util.Optional;

/**
 * Interprète l'argument « depuis » d'une liaison de document.
 * Accepte une durée ISO-8601 relative (P3M, P1Y, P15D) ou une date absolue (2026-01-31).
 * Une durée relative est volontairement privilégiée dans les gabarits :
 * « la biologie des 3 derniers mois » reste vraie quel que soit le jour de rédaction.
 */
public final class FenetreTemporelle {

    private FenetreTemporelle() {
    }

    public static Optional<LocalDate> debut(String depuis, LocalDate aujourdhui) {
        if (depuis == null || depuis.isBlank()) {
            return Optional.empty();
        }
        String valeur = depuis.trim();
        if (valeur.startsWith("P") || valeur.startsWith("p")) {
            return Optional.of(aujourdhui.minus(analyserDuree(valeur)));
        }
        try {
            return Optional.of(LocalDate.parse(valeur));
        } catch (DateTimeParseException e) {
            throw new FenetreTemporelleInvalide(depuis);
        }
    }

    private static Period analyserDuree(String valeur) {
        try {
            Period duree = Period.parse(valeur);
            if (duree.isNegative()) {
                throw new FenetreTemporelleInvalide(valeur);
            }
            return duree;
        } catch (DateTimeParseException e) {
            throw new FenetreTemporelleInvalide(valeur);
        }
    }

    public static final class FenetreTemporelleInvalide extends RuntimeException {
        public FenetreTemporelleInvalide(String valeur) {
            super("Fenêtre temporelle invalide : '" + valeur + "' (attendu : P3M, P1Y ou 2026-01-31)");
        }
    }
}
