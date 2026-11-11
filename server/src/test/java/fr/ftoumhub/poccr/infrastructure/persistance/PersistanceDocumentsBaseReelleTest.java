package fr.ftoumhub.poccr.infrastructure.persistance;

import fr.ftoumhub.poccr.domaine.document.ActeursDocument;
import fr.ftoumhub.poccr.domaine.document.DestinataireExterieur;
import fr.ftoumhub.poccr.domaine.document.Document;
import fr.ftoumhub.poccr.domaine.professionnel.Adresse;
import fr.ftoumhub.poccr.domaine.professionnel.ProfessionnelSante;
import fr.ftoumhub.poccr.domaine.professionnel.Telephone;
import fr.ftoumhub.poccr.domaine.professionnel.TypeAdresse;
import fr.ftoumhub.poccr.domaine.professionnel.TypeTelephone;
import fr.ftoumhub.poccr.domaine.validation.NomDocument;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Adaptateurs JDBC des documents et du référentiel, sur un vrai PostgreSQL migré par Flyway.
 * Désactivé par défaut (mvn verify ne dépend d'aucune base) ; à lancer sur une base JETABLE :
 *
 * <pre>mvn test -Dtest=PersistanceDocumentsBaseReelleTest -Dpoc.base.test=jdbc:postgresql://localhost:5432/poccr_it</pre>
 *
 * Les tests vident les tables de documents : ne jamais pointer sur la base de travail.
 */
@EnabledIfSystemProperty(named = "poc.base.test", matches = "jdbc:postgresql:.+")
class PersistanceDocumentsBaseReelleTest {

    // données fictives de la migration V7
    private static final UUID CH_POC = UUID.fromString("e0000000-0000-0000-0000-000000000001");
    private static final UUID DURAND = UUID.fromString("a0000000-0000-0000-0000-000000000001");
    private static final UUID PETIT = UUID.fromString("a0000000-0000-0000-0000-000000000003");
    private static final UUID GIRARD = UUID.fromString("a0000000-0000-0000-0000-000000000004");
    private static final UUID ROUX = UUID.fromString("a0000000-0000-0000-0000-000000000005");
    private static final UUID MARTIN = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private static JdbcClient jdbc;
    private static DocumentsJdbc documents;
    private static ProfessionnelsSanteJdbc professionnels;
    private static EtablissementsJdbc etablissements;

    @BeforeAll
    static void migrer() {
        DriverManagerDataSource source = new DriverManagerDataSource(System.getProperty("poc.base.test"),
                System.getProperty("poc.base.utilisateur", "poccr"), System.getProperty("poc.base.mot-de-passe", "poccr"));
        Flyway.configure().dataSource(source).load().migrate();
        jdbc = JdbcClient.create(source);
        documents = new DocumentsJdbc(jdbc, new TransactionTemplate(new DataSourceTransactionManager(source)));
        professionnels = new ProfessionnelsSanteJdbc(jdbc);
        etablissements = new EtablissementsJdbc(jdbc);
    }

    @BeforeEach
    void viderLesDocuments() {
        jdbc.sql("delete from document").update();
    }

    private static Document document(String nom) {
        return Document.builder().id(UUID.randomUUID()).nom(new NomDocument(nom)).etablissementId(CH_POC)
                .creeLe(Instant.now().truncatedTo(ChronoUnit.MICROS)).construire();
    }

    // --- fiches ---------------------------------------------------------------------------------

    @Test
    void une_fiche_enregistree_se_relit_a_l_identique() {
        Document d = document("cr-martin").avecPatient(MARTIN);
        documents.enregistrer(d);

        assertThat(documents.parNom(d.nom())).contains(d);
        assertThat(documents.parIds(Set.of(d.id()))).containsEntry(d.id(), d);
        assertThat(documents.tous()).containsExactly(d);
    }

    @Test
    void enregistrer_une_fiche_existante_la_met_a_jour() {
        Document d = document("cr-martin");
        documents.enregistrer(d);
        Document renomme = d.renomme(new NomDocument("cr-martin-v2")).avecPatient(MARTIN);

        documents.enregistrer(renomme);

        assertThat(documents.parNom(new NomDocument("cr-martin"))).isEmpty();
        assertThat(documents.parNom(new NomDocument("cr-martin-v2"))).contains(renomme);
        assertThat(documents.tous()).hasSize(1);
    }

    // --- acteurs --------------------------------------------------------------------------------

    @Test
    void les_acteurs_enregistres_se_relisent_a_l_identique() {
        Document d = document("cr-martin");
        documents.enregistrer(d);
        ActeursDocument acteurs = ActeursDocument.saisis(List.of(DURAND, PETIT), GIRARD, List.of(ROUX),
                List.of(new DestinataireExterieur("Mutuelle POC", null, "contact@mutuelle.invalid")), true);

        documents.remplacerActeurs(d.id(), acteurs);

        assertThat(documents.acteursDe(Set.of(d.id()))).containsEntry(d.id(), acteurs);
    }

    @Test
    void les_acteurs_de_plusieurs_documents_se_chargent_ensemble() {
        Document avec = document("avec-acteurs");
        Document sans = document("sans-acteur");
        documents.enregistrer(avec);
        documents.enregistrer(sans);
        ActeursDocument acteurs = ActeursDocument.saisis(List.of(DURAND), null, List.of(), List.of(), false);
        documents.remplacerActeurs(avec.id(), acteurs);

        Map<UUID, ActeursDocument> charges = documents.acteursDe(Set.of(avec.id(), sans.id()));

        assertThat(charges).containsEntry(avec.id(), acteurs).containsEntry(sans.id(), ActeursDocument.AUCUN);
    }

    @Test
    void remplacer_les_acteurs_efface_les_precedents() {
        Document d = document("cr-martin");
        documents.enregistrer(d);
        documents.remplacerActeurs(d.id(), ActeursDocument.saisis(List.of(DURAND, PETIT), GIRARD, List.of(ROUX),
                List.of(), true));
        ActeursDocument nouveaux = ActeursDocument.saisis(List.of(PETIT), ROUX, List.of(), List.of(), false);

        documents.remplacerActeurs(d.id(), nouveaux);

        assertThat(documents.acteursDe(Set.of(d.id()))).containsEntry(d.id(), nouveaux);
    }

    @Test
    void un_remplacement_refuse_par_la_base_laisse_les_acteurs_precedents_intacts() {
        Document d = document("cr-martin");
        documents.enregistrer(d);
        ActeursDocument avant = ActeursDocument.saisis(List.of(DURAND), GIRARD, List.of(), List.of(), false);
        documents.remplacerActeurs(d.id(), avant);
        UUID inconnu = UUID.randomUUID();

        assertThatThrownBy(() -> documents.remplacerActeurs(d.id(),
                ActeursDocument.saisis(List.of(PETIT, inconnu), null, List.of(), List.of(), false)))
                .isInstanceOf(RuntimeException.class);

        assertThat(documents.acteursDe(Set.of(d.id()))).containsEntry(d.id(), avant);
    }

    @Test
    void supprimer_une_fiche_supprime_ses_acteurs() {
        Document d = document("cr-martin");
        documents.enregistrer(d);
        documents.remplacerActeurs(d.id(), ActeursDocument.saisis(List.of(DURAND), GIRARD, List.of(), List.of(), true));

        documents.supprimer(d.id());

        assertThat(documents.parNom(d.nom())).isEmpty();
        assertThat(jdbc.sql("select count(*) from document_signataire").query(Long.class).single()).isZero();
        assertThat(jdbc.sql("select count(*) from document_destinataire").query(Long.class).single()).isZero();
    }

    // --- référentiel ----------------------------------------------------------------------------

    @Test
    void les_professionnels_se_chargent_par_lot() {
        Map<UUID, ProfessionnelSante> charges = professionnels.parIds(Set.of(DURAND, GIRARD, UUID.randomUUID()));

        assertThat(charges).containsOnlyKeys(DURAND, GIRARD);
        assertThat(charges.get(DURAND).libelle()).isEqualTo("Dr Sophie DURAND");
        assertThat(charges.get(DURAND).identifiantNational()).isEqualTo("899000000011");
    }

    @Test
    void la_recherche_de_professionnels_porte_sur_le_debut_du_nom_du_prenom_ou_du_code() {
        assertThat(professionnels.rechercher("pe", 10)).extracting(ProfessionnelSante::nom).containsExactly("PETIT");
        assertThat(professionnels.rechercher("Sophie", 10)).extracting(ProfessionnelSante::nom).containsExactly("DURAND");
        assertThat(professionnels.rechercher("r", 1)).hasSize(1);
        assertThat(professionnels.rechercher("%", 10)).isEmpty();
        assertThat(professionnels.rechercher("_", 10)).isEmpty();
    }

    @Test
    void les_adresses_se_chargent_pour_les_professionnels_et_les_etablissements() {
        Map<UUID, List<Adresse>> adresses = professionnels.adressesDe(Set.of(PETIT, CH_POC));

        assertThat(adresses.get(PETIT)).singleElement().satisfies(a -> {
            assertThat(a.type()).isEqualTo(TypeAdresse.PROFESSIONNELLE);
            assertThat(a.ville()).isEqualTo("Lyon");
        });
        assertThat(adresses.get(CH_POC)).extracting(Adresse::type).containsExactly(TypeAdresse.PRINCIPALE);

        UUID adressePetit = adresses.get(PETIT).get(0).id();
        assertThat(professionnels.telephonesDe(Set.of(adressePetit)).get(adressePetit))
                .extracting(Telephone::type)
                .containsExactlyInAnyOrder(TypeTelephone.FIXE, TypeTelephone.PORTABLE);
    }

    @Test
    void l_etablissement_par_defaut_est_celui_du_poc() {
        assertThat(etablissements.parDefaut().id()).isEqualTo(CH_POC);
        assertThat(etablissements.parIds(Set.of(CH_POC))).containsOnlyKeys(CH_POC);
    }
}
