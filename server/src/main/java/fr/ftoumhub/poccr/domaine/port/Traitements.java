package fr.ftoumhub.poccr.domaine.port;

import fr.ftoumhub.poccr.domaine.traitement.Traitement;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Port secondaire : chargement groupé des traitements. */
public interface Traitements {

    Map<UUID, List<Traitement>> dePatients(Set<UUID> patientIds);
}
