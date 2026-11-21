package fr.ftoumhub.poccr.domaine.dossier;

import java.util.Comparator;
import java.util.List;

/** Règles de sélection communes aux listes datées du dossier. */
public final class Selection {

    private Selection() {
    }

    /**
     * Les {@code n} éléments les plus récents, du plus récent au plus ancien ; tous si {@code n} est null.
     *
     * @throws IllegalArgumentException si {@code n} est négatif ou nul
     */
    public static <T, D extends Comparable<? super D>> List<T> derniers(List<T> elements,
                                                                        java.util.function.Function<T, D> date,
                                                                        Integer n) {
        if (n != null && n < 1) {
            throw new IllegalArgumentException("Le nombre d'éléments demandés doit être positif : " + n);
        }
        return elements.stream()
                .sorted(Comparator.comparing(date).reversed())
                .limit(n == null ? Long.MAX_VALUE : n)
                .toList();
    }
}
