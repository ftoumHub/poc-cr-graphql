package fr.ftoumhub.poccr.domaine.port;

import fr.ftoumhub.poccr.domaine.ResultatPage;
import fr.ftoumhub.poccr.domaine.patient.FiltrePatients;
import fr.ftoumhub.poccr.domaine.patient.Patient;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Port secondaire : accès aux patients. */
public interface Patients {

    Optional<Patient> parId(UUID id);

    /** Recherche par IPP exact ou début de nom/prénom, insensible à la casse. */
    List<Patient> rechercher(String texte, int limite);

    /** Liste paginée, filtrable colonne par colonne (page d'accueil). */
    ResultatPage<Patient> lister(FiltrePatients filtre, int page, int taille);
}
