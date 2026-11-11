package fr.ftoumhub.poccr.application;

import fr.ftoumhub.poccr.domaine.document.ActeursDocument;
import fr.ftoumhub.poccr.domaine.document.Document;
import fr.ftoumhub.poccr.domaine.etablissement.Etablissement;
import fr.ftoumhub.poccr.domaine.port.ArchiveValidations;
import fr.ftoumhub.poccr.domaine.port.Documents;
import fr.ftoumhub.poccr.domaine.port.Etablissements;
import fr.ftoumhub.poccr.domaine.port.ProfessionnelsSante;
import fr.ftoumhub.poccr.domaine.professionnel.Adresse;
import fr.ftoumhub.poccr.domaine.professionnel.Coordonnees;
import fr.ftoumhub.poccr.domaine.professionnel.ProfessionnelSante;
import fr.ftoumhub.poccr.domaine.professionnel.Telephone;
import fr.ftoumhub.poccr.domaine.validation.Validation;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Cas d'utilisation : consulter un document et ses acteurs pour alimenter un compte rendu.
 * Lecture seule ; toutes les lectures sont groupées (une requête par lot, pas par élément).
 */
public class ConsultationDocuments {

    private final Documents documents;
    private final ProfessionnelsSante professionnels;
    private final Etablissements etablissements;
    private final ArchiveValidations validations;

    public ConsultationDocuments(Documents documents, ProfessionnelsSante professionnels,
                                 Etablissements etablissements, ArchiveValidations validations) {
        this.documents = documents;
        this.professionnels = professionnels;
        this.etablissements = etablissements;
        this.validations = validations;
    }

    public Optional<Document> document(UUID id) {
        return Optional.ofNullable(documents.parIds(Set.of(id)).get(id));
    }

    public Map<UUID, ActeursDocument> acteursDe(Set<UUID> documentIds) {
        return documents.acteursDe(documentIds);
    }

    public Map<UUID, ProfessionnelSante> professionnelsDe(Set<UUID> ids) {
        return professionnels.parIds(ids);
    }

    public Map<UUID, Etablissement> etablissementsDe(Set<UUID> ids) {
        return etablissements.parIds(ids);
    }

    /** Adresses et téléphones de chaque propriétaire : deux lectures, quel que soit le nombre de propriétaires. */
    public Map<UUID, Coordonnees> coordonneesDe(Set<UUID> proprietaireIds) {
        Map<UUID, List<Adresse>> adresses = professionnels.adressesDe(proprietaireIds);
        Set<UUID> adresseIds = adresses.values().stream().flatMap(List::stream).map(Adresse::id)
                .collect(Collectors.toSet());
        Map<UUID, List<Telephone>> telephones = professionnels.telephonesDe(adresseIds);
        Map<UUID, Coordonnees> resultat = new HashMap<>();
        for (UUID proprietaire : proprietaireIds) {
            List<Adresse> siennes = adresses.getOrDefault(proprietaire, List.of());
            List<Telephone> tels = siennes.stream()
                    .flatMap(a -> telephones.getOrDefault(a.id(), List.of()).stream()).toList();
            resultat.put(proprietaire, new Coordonnees(siennes, tels));
        }
        return resultat;
    }

    public Optional<Validation> validation(Document document) {
        return validations.deDocument(document.nom());
    }

    public List<ProfessionnelSante> rechercherProfessionnels(String texte) {
        if (texte == null || texte.trim().length() < 2) {
            return List.of();
        }
        return professionnels.rechercher(texte, 20);
    }
}
