package fr.ftoumhub.poccr.infrastructure.editeur;

import fr.ftoumhub.poccr.application.ConsultationDocuments;
import fr.ftoumhub.poccr.application.ConsultationDossierPatient;
import fr.ftoumhub.poccr.application.ConsultationOrganisation;
import fr.ftoumhub.poccr.domaine.sejour.Sejour;
import fr.ftoumhub.poccr.domaine.sejour.UniteFonctionnelle;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Clé de contexte de la page hôte : organisation (établissement > service > UF), séjours d'un patient,
 * professionnels pour le « professionnel connecté » simulé (pas d'authentification dans le POC).
 */
@RestController
@RequestMapping("/api/contexte")
public class ControleurContexte {

    public record UniteVue(String code, String libelle) {
    }

    public record ServiceVue(String code, String libelle, List<UniteVue> unites) {
    }

    public record EtablissementVue(UUID id, String libelle, String finess, List<ServiceVue> services) {
    }

    public record UfSejourVue(String code, String libelle, String serviceCode, String service) {
        static UfSejourVue de(UniteFonctionnelle u) {
            return u == null ? null : new UfSejourVue(u.code(), u.libelle(), u.serviceCode(), u.serviceLibelle());
        }
    }

    public record SejourVue(UUID id, String numero, String type, String dateEntree, String dateSortie, String service,
                            String motif, UfSejourVue uf, boolean enCours, boolean reference) {
    }

    public record ProfessionnelVue(UUID id, String libelle, String qualite, String specialites) {
    }

    private final ConsultationOrganisation organisation;
    private final ConsultationDossierPatient dossier;
    private final ConsultationDocuments documents;

    public ControleurContexte(ConsultationOrganisation organisation, ConsultationDossierPatient dossier,
                              ConsultationDocuments documents) {
        this.organisation = organisation;
        this.dossier = dossier;
        this.documents = documents;
    }

    @GetMapping("/organisation")
    public List<EtablissementVue> organisation() {
        return organisation.arbre().stream()
                .map(e -> new EtablissementVue(e.etablissement().id(), e.etablissement().libelle(),
                        e.etablissement().finess(), e.services().stream()
                        .map(s -> new ServiceVue(s.service().code(), s.service().libelle(),
                                s.unites().stream().map(u -> new UniteVue(u.code(), u.libelle())).toList()))
                        .toList()))
                .toList();
    }

    /** Séjours du patient, du plus récent au plus ancien ; « reference » = séjour proposé par défaut. */
    @GetMapping("/patients/{id}/sejours")
    public List<SejourVue> sejours(@PathVariable UUID id) {
        List<Sejour> sejours = dossier.sejoursDe(Set.of(id)).getOrDefault(id, List.of());
        Optional<UUID> reference = dossier.sejourDeReference(sejours).map(Sejour::id);
        return sejours.stream()
                .map(s -> new SejourVue(s.id(), s.numero(), s.type().name(), s.dateEntree().toString(),
                        s.dateSortie() == null ? null : s.dateSortie().toString(), s.service(), s.motif(),
                        UfSejourVue.de(s.ufMedicale()), dossier.estEnCours(s), reference.map(s.id()::equals).orElse(false)))
                .toList();
    }

    @GetMapping("/professionnels")
    public List<ProfessionnelVue> professionnels() {
        return documents.professionnels().stream()
                .map(p -> new ProfessionnelVue(p.id(), p.libelle(), p.qualite(), p.specialites()))
                .toList();
    }
}
