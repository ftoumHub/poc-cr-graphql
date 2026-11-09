package fr.ftoumhub.poccr.domaine.port;

import fr.ftoumhub.poccr.domaine.sejour.Sejour;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Port secondaire : chargement groupé des séjours, du plus récent au plus ancien. */
public interface Sejours {

    Map<UUID, List<Sejour>> dePatients(Set<UUID> patientIds);
}
