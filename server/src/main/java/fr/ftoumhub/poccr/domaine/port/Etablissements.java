package fr.ftoumhub.poccr.domaine.port;

import fr.ftoumhub.poccr.domaine.etablissement.Etablissement;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Port secondaire : établissements émetteurs. */
public interface Etablissements {

    Map<UUID, Etablissement> parIds(Set<UUID> ids);

    /** Établissement des documents créés dans le POC (un seul établissement en attendant l'authentification). */
    Etablissement parDefaut();
}
