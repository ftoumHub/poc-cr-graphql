package fr.ftoumhub.poccr.domaine.port;

import fr.ftoumhub.poccr.domaine.professionnel.Adresse;
import fr.ftoumhub.poccr.domaine.professionnel.ProfessionnelSante;
import fr.ftoumhub.poccr.domaine.professionnel.Telephone;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Port secondaire : référentiel des professionnels de santé et coordonnées (chargements groupés). */
public interface ProfessionnelsSante {

    Map<UUID, ProfessionnelSante> parIds(Set<UUID> ids);

    /** Début de nom, de prénom ou code ; au plus {@code limite} résultats, triés par nom. */
    List<ProfessionnelSante> rechercher(String texte, int limite);

    /** Adresses par propriétaire (professionnel ou établissement). */
    Map<UUID, List<Adresse>> adressesDe(Set<UUID> proprietaireIds);

    Map<UUID, List<Telephone>> telephonesDe(Set<UUID> adresseIds);
}
