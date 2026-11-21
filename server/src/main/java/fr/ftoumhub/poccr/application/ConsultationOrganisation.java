package fr.ftoumhub.poccr.application;

import fr.ftoumhub.poccr.domaine.etablissement.Etablissement;
import fr.ftoumhub.poccr.domaine.organisation.Service;
import fr.ftoumhub.poccr.domaine.port.Organisation;
import fr.ftoumhub.poccr.domaine.sejour.UniteFonctionnelle;

import java.util.List;
import java.util.Optional;

/**
 * Cas d'utilisation : parcourir l'organisation (établissement > service > unité fonctionnelle) pour situer
 * la navigation et les documents. Lecture seule.
 */
public class ConsultationOrganisation {

    /** Établissement, ses services et leurs UF, dans l'ordre d'affichage. */
    public record EtablissementOrganise(Etablissement etablissement, List<ServiceOrganise> services) {
    }

    public record ServiceOrganise(Service service, List<UniteFonctionnelle> unites) {
    }

    private final Organisation organisation;

    public ConsultationOrganisation(Organisation organisation) {
        this.organisation = organisation;
    }

    public List<EtablissementOrganise> arbre() {
        List<Service> services = organisation.services();
        List<UniteFonctionnelle> unites = organisation.unitesFonctionnelles();
        return organisation.etablissements().stream()
                .map(e -> new EtablissementOrganise(e, services.stream()
                        .filter(s -> s.etablissementId().equals(e.id()))
                        .map(s -> new ServiceOrganise(s, unites.stream()
                                .filter(u -> s.code().equals(u.serviceCode())).toList()))
                        .toList()))
                .toList();
    }

    public Optional<UniteFonctionnelle> uniteFonctionnelle(String code) {
        return code == null ? Optional.empty() : organisation.uniteFonctionnelle(code);
    }
}
