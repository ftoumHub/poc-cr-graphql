package fr.ftoumhub.poccr.domaine;

import java.util.List;

/** Une page de résultats, avec le total toutes pages confondues (pour la pagination). */
public record ResultatPage<T>(List<T> elements, long total) {
}
