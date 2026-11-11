package fr.ftoumhub.poccr.infrastructure.graphql;

import fr.ftoumhub.poccr.application.ConsultationDocuments;
import fr.ftoumhub.poccr.domaine.document.ActeursDocument;
import fr.ftoumhub.poccr.domaine.document.DestinataireExterieur;
import fr.ftoumhub.poccr.domaine.document.Document;
import fr.ftoumhub.poccr.domaine.etablissement.Etablissement;
import fr.ftoumhub.poccr.domaine.port.ArchiveValidations;
import fr.ftoumhub.poccr.domaine.port.Documents;
import fr.ftoumhub.poccr.domaine.port.Etablissements;
import fr.ftoumhub.poccr.domaine.port.ProfessionnelsSante;
import fr.ftoumhub.poccr.domaine.professionnel.Adresse;
import fr.ftoumhub.poccr.domaine.professionnel.Civilite;
import fr.ftoumhub.poccr.domaine.professionnel.ProfessionnelSante;
import fr.ftoumhub.poccr.domaine.professionnel.Telephone;
import fr.ftoumhub.poccr.domaine.professionnel.TypeAdresse;
import fr.ftoumhub.poccr.domaine.professionnel.TypeMedecin;
import fr.ftoumhub.poccr.domaine.professionnel.TypeTelephone;
import fr.ftoumhub.poccr.domaine.validation.NomDocument;
import fr.ftoumhub.poccr.domaine.validation.Validation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.test.autoconfigure.graphql.GraphQlTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.graphql.test.tester.GraphQlTester;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tranche GraphQL de la racine document, ports en mémoire : contrat des champs (noms du CDF)
 * et absence de N+1 (le nombre de lectures ne dépend pas du nombre d'acteurs).
 */
@GraphQlTest
@Import({ControleurDocument.class, GestionErreursGraphQl.class, ControleurDocumentTest.PortsEnMemoire.class})
class ControleurDocumentTest {

    static final UUID CH_POC = UUID.randomUUID();
    static final UUID CR_SIMPLE = UUID.randomUUID();
    static final UUID CR_VOLUMINEUX = UUID.randomUUID();
    static final UUID CR_VIERGE = UUID.randomUUID();
    static final UUID CR_VALIDE = UUID.randomUUID();
    static final UUID DURAND = UUID.randomUUID();
    static final UUID GIRARD = UUID.randomUUID();
    /** 23:30 UTC le 11 = 00:30 le 12 à Paris */
    static final Instant CREATION = Instant.parse("2026-11-11T23:30:00Z");
    static final Instant VALIDATION = Instant.parse("2026-11-12T09:15:00Z");

    @Autowired
    GraphQlTester graphQlTester;

    @Autowired
    Journal journal;

    @BeforeEach
    void effacerLeJournal() {
        journal.lectures.clear();
    }

    @Test
    void le_document_expose_ses_signataires_dans_l_ordre_de_signature() {
        graphQlTester.document("""
                        query($id: ID!) { document(id: $id) {
                          c0: signatairePrincipal { medecin { nom } }
                          c1: signatairesSecondaires { medecin { nom } }
                          c2: signataires { medecin { code } }
                        } }""")
                .variable("id", CR_VOLUMINEUX)
                .execute()
                .path("document.c0.medecin.nom").entity(String.class).isEqualTo("DURAND")
                .path("document.c1[*].medecin.nom").entityList(String.class)
                .containsExactly("PS1", "PS2", "PS3", "PS4", "PS5")
                .path("document.c2[0].medecin.code").entity(String.class).isEqualTo("DURAND");
    }

    @Test
    void le_destinataire_reprend_les_coordonnees_professionnelles_du_professionnel() {
        graphQlTester.document("""
                        query($id: ID!) { document(id: $id) {
                          destinataire {
                            nom prenom civilite libelle code identifiantNational specialites email
                            telephone fax telephoneAutre
                            adresse { ligne1 ville telephone(type: FAX) { numero } }
                            medecin { typeMedecin immatriculations { adeli } identifiantNationalActif { identifiantNational } }
                          }
                        } }""")
                .variable("id", CR_SIMPLE)
                .execute()
                .path("document.destinataire.nom").entity(String.class).isEqualTo("GIRARD")
                .path("document.destinataire.civilite").entity(String.class).isEqualTo("DOCTEUR")
                .path("document.destinataire.libelle").entity(String.class).isEqualTo("Dr Paul GIRARD")
                .path("document.destinataire.identifiantNational").entity(String.class).isEqualTo("899000000044")
                .path("document.destinataire.telephone").entity(String.class).isEqualTo("04 00 00 04 04")
                .path("document.destinataire.fax").entity(String.class).isEqualTo("04 00 00 04 99")
                .path("document.destinataire.telephoneAutre").valueIsNull()
                .path("document.destinataire.adresse.ville").entity(String.class).isEqualTo("Villeurbanne")
                .path("document.destinataire.adresse.telephone.numero").entity(String.class).isEqualTo("04 00 00 04 99")
                .path("document.destinataire.medecin.typeMedecin").entity(String.class).isEqualTo("CORRESPONDANT")
                .path("document.destinataire.medecin.immatriculations[*].adeli").entityList(String.class)
                .containsExactly("990000044")
                .path("document.destinataire.medecin.identifiantNationalActif.identifiantNational")
                .entity(String.class).isEqualTo("899000000044");
    }

    @Test
    void les_destinataires_exterieurs_et_la_copie_au_patient_sont_exposes() {
        graphQlTester.document("""
                        query($id: ID!) { document(id: $id) {
                          destinatairesExterieurs { nom prenom email }
                          destinatairePatient
                        } }""")
                .variable("id", CR_SIMPLE)
                .execute()
                .path("document.destinatairesExterieurs[*].nom").entityList(String.class).containsExactly("Mutuelle POC")
                .path("document.destinatairePatient").entity(Boolean.class).isEqualTo(true);
    }

    @Test
    void un_document_sans_acteur_ni_validation_renvoie_des_valeurs_vides() {
        graphQlTester.document("""
                        query($id: ID!) { document(id: $id) {
                          signatairePrincipal { medecin { nom } }
                          signataires { medecin { nom } }
                          destinataire { nom }
                          destinatairesEnCopie { nom }
                          destinatairePatient
                          psCreateur { nom }
                          validation { dateValidation }
                        } }""")
                .variable("id", CR_VIERGE)
                .execute()
                .path("document.signatairePrincipal").valueIsNull()
                .path("document.signataires").entityList(Object.class).hasSize(0)
                .path("document.destinataire").valueIsNull()
                .path("document.destinatairesEnCopie").entityList(Object.class).hasSize(0)
                .path("document.destinatairePatient").entity(Boolean.class).isEqualTo(false)
                .path("document.psCreateur").valueIsNull()
                .path("document.validation").valueIsNull();
    }

    @Test
    void les_dates_et_heures_sont_celles_de_paris() {
        graphQlTester.document("""
                        query($id: ID!) { document(id: $id) {
                          dateCreation heureCreation dateValeur
                          validation { dateValidation heureValidation }
                        } }""")
                .variable("id", CR_VALIDE)
                .execute()
                .path("document.dateCreation").entity(String.class).isEqualTo("2026-11-12")
                .path("document.heureCreation").entity(String.class).isEqualTo("00:30")
                .path("document.dateValeur").valueIsNull()
                .path("document.validation.dateValidation").entity(String.class).isEqualTo("2026-11-12")
                .path("document.validation.heureValidation").entity(String.class).isEqualTo("10:15");
    }

    @Test
    void l_etablissement_emetteur_et_ses_coordonnees_sont_exposes() {
        graphQlTester.document("""
                        query($id: ID!) { document(id: $id) {
                          etablissement { libelle finess adresse { ville } telephonePrincipal { numero } telephoneSecondaire { numero } }
                        } }""")
                .variable("id", CR_SIMPLE)
                .execute()
                .path("document.etablissement.libelle").entity(String.class).isEqualTo("Centre hospitalier POC")
                .path("document.etablissement.adresse.ville").entity(String.class).isEqualTo("Lyon")
                .path("document.etablissement.telephonePrincipal.numero").entity(String.class).isEqualTo("04 00 00 00 00")
                .path("document.etablissement.telephoneSecondaire").valueIsNull();
    }

    @Test
    void un_document_inconnu_renvoie_null() {
        graphQlTester.document("query($id: ID!) { document(id: $id) { titre } }")
                .variable("id", UUID.randomUUID())
                .execute()
                .path("document").valueIsNull();
    }

    @Test
    void le_nombre_de_lectures_ne_depend_pas_du_nombre_d_acteurs() {
        String requete = """
                query($id: ID!) { document(id: $id) {
                  c0: signataires { medecin { nom adresse { ville telephone { numero } } } }
                  c1: signatairePrincipal { medecin { nom } }
                  c2: destinataire { nom libelle fax adresse { ligne1 } }
                  c3: destinatairesEnCopie { nom telephone medecin { code } }
                  c4: etablissement { libelle adresse { ville } telephonePrincipal { numero } }
                } }""";

        graphQlTester.document(requete).variable("id", CR_SIMPLE).execute().path("document.c0").hasValue();
        Map<String, Long> simple = journal.parPort();
        journal.lectures.clear();
        graphQlTester.document(requete).variable("id", CR_VOLUMINEUX).execute()
                .path("document.c3[*].nom").entityList(String.class).hasSize(5);
        Map<String, Long> volumineux = journal.parPort();

        assertThat(volumineux).isEqualTo(simple);
        assertThat(volumineux).containsEntry("acteursDe", 1L).containsEntry("professionnels", 1L)
                .containsEntry("etablissements", 1L);
        // une lecture d'adresses (et de téléphones) par niveau de la requête qui en demande, pas par acteur
        assertThat(volumineux.get("adressesDe")).isLessThanOrEqualTo(2L);
        assertThat(volumineux.get("telephonesDe")).isEqualTo(volumineux.get("adressesDe"));
    }

    // --- ports en mémoire -----------------------------------------------------------------------

    static class Journal {
        final List<String> lectures = new ArrayList<>();

        Map<String, Long> parPort() {
            return lectures.stream().collect(Collectors.groupingBy(s -> s, Collectors.counting()));
        }
    }

    @SpringBootConfiguration
    static class Contexte {
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class PortsEnMemoire {

        @Bean
        Journal journal() {
            return new Journal();
        }

        @Bean
        ConsultationDocuments consultationDocuments(Journal journal) {
            Map<UUID, ProfessionnelSante> professionnels = new HashMap<>();
            Map<UUID, List<Adresse>> adresses = new HashMap<>();
            Map<UUID, List<Telephone>> telephones = new HashMap<>();
            ajouter(professionnels, adresses, telephones, ps(DURAND, "DURAND", "Sophie", "99000000011", null,
                    TypeMedecin.ETABLISSEMENT), "Lyon", Map.of(TypeTelephone.FIXE, "04 00 00 01 01"));
            ajouter(professionnels, adresses, telephones, ps(GIRARD, "GIRARD", "Paul", "99000000044", "990000044",
                    TypeMedecin.CORRESPONDANT), "Villeurbanne",
                    Map.of(TypeTelephone.FIXE, "04 00 00 04 04", TypeTelephone.FAX, "04 00 00 04 99"));
            List<UUID> autres = IntStream.rangeClosed(1, 5).mapToObj(i -> {
                UUID id = UUID.randomUUID();
                ajouter(professionnels, adresses, telephones, ps(id, "PS" + i, null, null, null, TypeMedecin.CORRESPONDANT),
                        "Lyon", Map.of(TypeTelephone.FIXE, "04 00 00 00 0" + i));
                return id;
            }).toList();
            Adresse adresseCh = Adresse.builder().id(UUID.randomUUID()).proprietaireId(CH_POC)
                    .type(TypeAdresse.PRINCIPALE).ligne1("1 avenue du POC").ville("Lyon").construire();
            adresses.put(CH_POC, List.of(adresseCh));
            telephones.put(adresseCh.id(), List.of(new Telephone(UUID.randomUUID(), adresseCh.id(),
                    TypeTelephone.PRINCIPAL, "04 00 00 00 00")));
            Etablissement ch = Etablissement.builder().id(CH_POC).finess("990000001")
                    .libelle("Centre hospitalier POC").construire();

            Map<UUID, Document> documents = new HashMap<>();
            Map<UUID, ActeursDocument> acteurs = new HashMap<>();
            documents.put(CR_SIMPLE, document(CR_SIMPLE, "cr-simple"));
            acteurs.put(CR_SIMPLE, ActeursDocument.saisis(List.of(DURAND), GIRARD, List.of(),
                    List.of(new DestinataireExterieur("Mutuelle POC", null, null)), true));
            documents.put(CR_VOLUMINEUX, document(CR_VOLUMINEUX, "cr-volumineux"));
            List<UUID> signataires = new ArrayList<>(List.of(DURAND));
            signataires.addAll(autres);
            acteurs.put(CR_VOLUMINEUX, ActeursDocument.saisis(signataires, GIRARD, autres, List.of(), false));
            documents.put(CR_VIERGE, document(CR_VIERGE, "cr-vierge"));
            documents.put(CR_VALIDE, document(CR_VALIDE, "cr-valide"));

            Documents portDocuments = new Documents() {
                @Override public Optional<Document> parNom(NomDocument nom) { throw new UnsupportedOperationException(); }
                @Override public List<Document> tous() { throw new UnsupportedOperationException(); }
                @Override public void enregistrer(Document document) { throw new UnsupportedOperationException(); }
                @Override public void supprimer(UUID documentId) { throw new UnsupportedOperationException(); }
                @Override public void remplacerActeurs(UUID id, ActeursDocument a) { throw new UnsupportedOperationException(); }

                @Override
                public Map<UUID, Document> parIds(Set<UUID> ids) {
                    return documents.entrySet().stream().filter(e -> ids.contains(e.getKey()))
                            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
                }

                @Override
                public Map<UUID, ActeursDocument> acteursDe(Set<UUID> ids) {
                    journal.lectures.add("acteursDe");
                    Map<UUID, ActeursDocument> resultat = new HashMap<>();
                    ids.forEach(id -> resultat.put(id, acteurs.getOrDefault(id, ActeursDocument.AUCUN)));
                    return resultat;
                }
            };
            ProfessionnelsSante portProfessionnels = new ProfessionnelsSante() {
                @Override
                public Map<UUID, ProfessionnelSante> parIds(Set<UUID> ids) {
                    journal.lectures.add("professionnels");
                    return professionnels.entrySet().stream().filter(e -> ids.contains(e.getKey()))
                            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
                }

                @Override
                public List<ProfessionnelSante> rechercher(String texte, int limite) {
                    return List.of();
                }

                @Override
                public Map<UUID, List<Adresse>> adressesDe(Set<UUID> ids) {
                    journal.lectures.add("adressesDe");
                    return adresses.entrySet().stream().filter(e -> ids.contains(e.getKey()))
                            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
                }

                @Override
                public Map<UUID, List<Telephone>> telephonesDe(Set<UUID> ids) {
                    journal.lectures.add("telephonesDe");
                    return telephones.entrySet().stream().filter(e -> ids.contains(e.getKey()))
                            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
                }
            };
            Etablissements portEtablissements = new Etablissements() {
                @Override
                public Map<UUID, Etablissement> parIds(Set<UUID> ids) {
                    journal.lectures.add("etablissements");
                    return ids.contains(CH_POC) ? Map.of(CH_POC, ch) : Map.of();
                }

                @Override
                public Etablissement parDefaut() {
                    return ch;
                }
            };
            ArchiveValidations validations = new ArchiveValidations() {
                @Override
                public Optional<Validation> deDocument(NomDocument document) {
                    return document.valeur().equals("cr-valide")
                            ? Optional.of(Validation.builder().document("cr-valide").patientId(UUID.randomUUID())
                            .requete("q").donneesJson("{}").valideLe(VALIDATION).validePar("Dr DURAND").construire())
                            : Optional.empty();
                }

                @Override
                public void enregistrer(Validation validation) {
                }
            };
            return new ConsultationDocuments(portDocuments, portProfessionnels, portEtablissements, validations);
        }

        private static Document document(UUID id, String nom) {
            return Document.builder().id(id).nom(new NomDocument(nom)).etablissementId(CH_POC).creeLe(CREATION)
                    .construire();
        }

        private static ProfessionnelSante ps(UUID id, String nom, String prenom, String rpps, String adeli,
                                             TypeMedecin type) {
            return ProfessionnelSante.builder().id(id).code(nom).nom(nom).prenom(prenom).titre("Dr")
                    .civilite(Civilite.DOCTEUR).rpps(rpps).adeli(adeli).type(type).construire();
        }

        private static void ajouter(Map<UUID, ProfessionnelSante> professionnels, Map<UUID, List<Adresse>> adresses,
                                    Map<UUID, List<Telephone>> telephones, ProfessionnelSante ps, String ville,
                                    Map<TypeTelephone, String> numeros) {
            professionnels.put(ps.id(), ps);
            Adresse adresse = Adresse.builder().id(UUID.randomUUID()).proprietaireId(ps.id())
                    .type(TypeAdresse.PROFESSIONNELLE).ligne1("Cabinet").ville(ville).construire();
            adresses.put(ps.id(), List.of(adresse));
            telephones.put(adresse.id(), numeros.entrySet().stream()
                    .map(e -> new Telephone(UUID.randomUUID(), adresse.id(), e.getKey(), e.getValue())).toList());
        }
    }
}
