package fr.ftoumhub.poccr.infrastructure.graphql;

import fr.ftoumhub.poccr.application.ConsultationOrganisation;
import fr.ftoumhub.poccr.domaine.document.Document;
import fr.ftoumhub.poccr.domaine.sejour.Sejour;
import fr.ftoumhub.poccr.domaine.sejour.UniteFonctionnelle;
import graphql.schema.DataFetchingEnvironment;
import org.dataloader.DataLoader;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.stereotype.Controller;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Clé de contexte du document en GraphQL : séjour et unité fonctionnelle figés à la création. Le séjour
 * vient du chargeur « sejours » (par patient) de {@link ControleurDossierPatient}.
 */
@Controller
public class ControleurContexteDocument {

    private final ConsultationOrganisation organisation;

    public ControleurContexteDocument(ConsultationOrganisation organisation) {
        this.organisation = organisation;
    }

    @SchemaMapping(typeName = "Document")
    public CompletableFuture<Sejour> sejour(Document document, DataFetchingEnvironment env) {
        if (document.sejourId() == null || document.patientId() == null) {
            return CompletableFuture.completedFuture(null);
        }
        DataLoader<UUID, List<Sejour>> chargeur = env.getDataLoader(ControleurDossierPatient.CHARGEUR_SEJOURS);
        return chargeur.load(document.patientId()).thenApply(sejours -> sejours == null ? null : sejours.stream()
                .filter(s -> s.id().equals(document.sejourId())).findFirst().orElse(null));
    }

    @SchemaMapping(typeName = "Document")
    public UniteFonctionnelle uniteFonctionnelle(Document document) {
        return organisation.uniteFonctionnelle(document.ufCode()).orElse(null);
    }

    @SchemaMapping(typeName = "UniteFonctionnelle")
    public String service(UniteFonctionnelle uf) {
        return uf.serviceLibelle();
    }
}
