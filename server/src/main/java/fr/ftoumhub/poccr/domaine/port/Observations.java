package fr.ftoumhub.poccr.domaine.port;

import fr.ftoumhub.poccr.domaine.observation.FiltreObservations;
import fr.ftoumhub.poccr.domaine.observation.Observation;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Port secondaire : chargement groupé des observations (pensé pour les DataLoaders). */
public interface Observations {

    /** Observations des patients demandés, triées de la plus récente à la plus ancienne. */
    Map<UUID, List<Observation>> dePatients(Set<UUID> patientIds, FiltreObservations filtre);
}
