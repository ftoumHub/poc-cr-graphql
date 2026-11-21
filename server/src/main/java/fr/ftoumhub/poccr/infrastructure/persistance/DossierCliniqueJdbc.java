package fr.ftoumhub.poccr.infrastructure.persistance;

import fr.ftoumhub.poccr.domaine.dossier.Allergie;
import fr.ftoumhub.poccr.domaine.dossier.Antecedent;
import fr.ftoumhub.poccr.domaine.dossier.FiltreFormulaires;
import fr.ftoumhub.poccr.domaine.dossier.Formulaire;
import fr.ftoumhub.poccr.domaine.dossier.LignePrescription;
import fr.ftoumhub.poccr.domaine.dossier.NaturePrescription;
import fr.ftoumhub.poccr.domaine.dossier.ObservationMedicale;
import fr.ftoumhub.poccr.domaine.dossier.PersonneEntourage;
import fr.ftoumhub.poccr.domaine.dossier.RoleEntourage;
import fr.ftoumhub.poccr.domaine.dossier.StatutPrescription;
import fr.ftoumhub.poccr.domaine.dossier.TransmissionCiblee;
import fr.ftoumhub.poccr.domaine.port.DossierClinique;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

public class DossierCliniqueJdbc implements DossierClinique {

    private final JdbcClient jdbc;

    public DossierCliniqueJdbc(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Map<UUID, List<Allergie>> allergiesDe(Set<UUID> patientIds) {
        return parPatient(patientIds, """
                select id, patient_id, categorie, libelle, texte, commentaire, date_debut, date_fin
                from allergie where patient_id in (:ids) order by date_debut nulls last, libelle
                """, Map.of(), (rs, i) -> Allergie.builder()
                .id(rs.getObject("id", UUID.class))
                .patientId(rs.getObject("patient_id", UUID.class))
                .categorie(rs.getString("categorie"))
                .libelle(rs.getString("libelle"))
                .texte(rs.getString("texte"))
                .commentaire(rs.getString("commentaire"))
                .dateDebut(date(rs.getDate("date_debut")))
                .dateFin(date(rs.getDate("date_fin")))
                .construire(), Allergie::patientId);
    }

    @Override
    public Map<UUID, List<Antecedent>> antecedentsDe(Set<UUID> patientIds) {
        return parPatient(patientIds, """
                select id, patient_id, categorie, libelle, statut, date_debut
                from antecedent where patient_id in (:ids) order by categorie, date_debut nulls last, libelle
                """, Map.of(), (rs, i) -> Antecedent.builder()
                .id(rs.getObject("id", UUID.class))
                .patientId(rs.getObject("patient_id", UUID.class))
                .categorie(rs.getString("categorie"))
                .libelle(rs.getString("libelle"))
                .statut(rs.getString("statut"))
                .dateDebut(date(rs.getDate("date_debut")))
                .construire(), Antecedent::patientId);
    }

    @Override
    public Map<UUID, List<LignePrescription>> prescriptionsDe(Set<UUID> patientIds, NaturePrescription nature,
                                                              StatutPrescription statut) {
        StringBuilder sql = new StringBuilder("""
                select id, patient_id, nature, statut, libelle, posologie, commentaire, date_debut, date_fin
                from ligne_prescription where patient_id in (:ids)
                """);
        Map<String, Object> parametres = new HashMap<>();
        if (nature != null) {
            sql.append(" and nature = :nature");
            parametres.put("nature", nature.name());
        }
        if (statut != null) {
            sql.append(" and statut = :statut");
            parametres.put("statut", statut.name());
        }
        sql.append(" order by date_debut desc, libelle");
        return parPatient(patientIds, sql.toString(), parametres, (rs, i) -> LignePrescription.builder()
                .id(rs.getObject("id", UUID.class))
                .patientId(rs.getObject("patient_id", UUID.class))
                .nature(NaturePrescription.valueOf(rs.getString("nature")))
                .statut(StatutPrescription.valueOf(rs.getString("statut")))
                .libelle(rs.getString("libelle"))
                .posologie(rs.getString("posologie"))
                .commentaire(rs.getString("commentaire"))
                .dateDebut(date(rs.getDate("date_debut")))
                .dateFin(date(rs.getDate("date_fin")))
                .construire(), LignePrescription::patientId);
    }

    @Override
    public Map<UUID, List<ObservationMedicale>> observationsMedicalesDe(Set<UUID> patientIds, String chapitre) {
        StringBuilder sql = new StringBuilder("""
                select id, patient_id, chapitre, ecrite_le, texte, auteur_id
                from observation_medicale where patient_id in (:ids)
                """);
        Map<String, Object> parametres = new HashMap<>();
        if (chapitre != null && !chapitre.isBlank()) {
            sql.append(" and lower(chapitre) = lower(:chapitre)");
            parametres.put("chapitre", chapitre.trim());
        }
        sql.append(" order by ecrite_le desc");
        return parPatient(patientIds, sql.toString(), parametres, (rs, i) -> ObservationMedicale.builder()
                .id(rs.getObject("id", UUID.class))
                .patientId(rs.getObject("patient_id", UUID.class))
                .chapitre(rs.getString("chapitre"))
                .ecriteLe(instant(rs.getTimestamp("ecrite_le")))
                .texte(rs.getString("texte"))
                .auteurId(rs.getObject("auteur_id", UUID.class))
                .construire(), ObservationMedicale::patientId);
    }

    @Override
    public Map<UUID, List<TransmissionCiblee>> transmissionsCibleesDe(Set<UUID> patientIds) {
        return parPatient(patientIds, """
                select id, patient_id, cible, ecrite_le, auteur, donnees, actions, resultats, etat
                from transmission_ciblee where patient_id in (:ids) order by ecrite_le desc
                """, Map.of(), (rs, i) -> TransmissionCiblee.builder()
                .id(rs.getObject("id", UUID.class))
                .patientId(rs.getObject("patient_id", UUID.class))
                .cible(rs.getString("cible"))
                .ecriteLe(instant(rs.getTimestamp("ecrite_le")))
                .auteur(rs.getString("auteur"))
                .donnees(rs.getString("donnees"))
                .actions(rs.getString("actions"))
                .resultats(rs.getString("resultats"))
                .etat(rs.getString("etat"))
                .construire(), TransmissionCiblee::patientId);
    }

    @Override
    public Map<UUID, List<PersonneEntourage>> entourageDe(Set<UUID> patientIds) {
        return parPatient(patientIds, """
                select id, patient_id, role, titre, nom, prenom, lien
                from personne_entourage where patient_id in (:ids) order by role, nom
                """, Map.of(), (rs, i) -> PersonneEntourage.builder()
                .id(rs.getObject("id", UUID.class))
                .patientId(rs.getObject("patient_id", UUID.class))
                .role(RoleEntourage.valueOf(rs.getString("role")))
                .titre(rs.getString("titre"))
                .nom(rs.getString("nom"))
                .prenom(rs.getString("prenom"))
                .lien(rs.getString("lien"))
                .construire(), PersonneEntourage::patientId);
    }

    /** Deux lectures : les instances (filtrées), puis toutes leurs valeurs. */
    @Override
    public Map<UUID, List<Formulaire>> formulairesDe(Set<UUID> patientIds, FiltreFormulaires filtre) {
        if (patientIds.isEmpty()) {
            return Map.of();
        }
        StringBuilder sql = new StringBuilder("""
                select f.id, f.patient_id, f.modele_code, m.libelle, f.cree_le, f.modifie_le, f.auteur
                from formulaire f join formulaire_modele m on m.code = f.modele_code
                where f.patient_id in (:ids)
                """);
        Map<String, Object> parametres = new HashMap<>();
        parametres.put("ids", List.copyOf(patientIds));
        filtre.modeleAttendu().ifPresent(m -> {
            sql.append(" and f.modele_code = :modele");
            parametres.put("modele", m);
        });
        filtre.rubriqueAttendue().ifPresent(r -> {
            sql.append(" and f.modele_code in (select modele_code from formulaire_rubrique_modele where code = :rubrique)");
            parametres.put("rubrique", r);
        });
        sql.append(" order by f.cree_le desc");
        record Entete(UUID id, UUID patientId, String modele, String libelle, Instant creeLe, Instant modifieLe,
                      String auteur) {
        }
        List<Entete> entetes = jdbc.sql(sql.toString()).params(parametres)
                .query((rs, i) -> new Entete(rs.getObject("id", UUID.class), rs.getObject("patient_id", UUID.class),
                        rs.getString("modele_code"), rs.getString("libelle"), instant(rs.getTimestamp("cree_le")),
                        instant(rs.getTimestamp("modifie_le")), rs.getString("auteur")))
                .list();
        if (entetes.isEmpty()) {
            return Map.of();
        }
        Map<UUID, Map<String, String>> valeurs = new HashMap<>();
        jdbc.sql("select formulaire_id, code, valeur from formulaire_valeur where formulaire_id in (:ids)")
                .param("ids", entetes.stream().map(Entete::id).toList())
                .query(rs -> {
                    valeurs.computeIfAbsent(rs.getObject("formulaire_id", UUID.class), k -> new HashMap<>())
                            .put(rs.getString("code"), rs.getString("valeur"));
                });
        return entetes.stream()
                .map(e -> Formulaire.builder().id(e.id()).patientId(e.patientId()).modele(e.modele())
                        .libelleModele(e.libelle()).creeLe(e.creeLe()).modifieLe(e.modifieLe()).auteur(e.auteur())
                        .valeurs(valeurs.getOrDefault(e.id(), Map.of())).construire())
                .collect(Collectors.groupingBy(Formulaire::patientId, LinkedHashMap::new, Collectors.toList()));
    }

    private <T> Map<UUID, List<T>> parPatient(Set<UUID> patientIds, String sql, Map<String, Object> parametres,
                                              RowMapper<T> lecteur, Function<T, UUID> patient) {
        if (patientIds.isEmpty()) {
            return Map.of();
        }
        return jdbc.sql(sql)
                .params(parametres)
                .param("ids", List.copyOf(patientIds))
                .query(lecteur)
                .list()
                .stream()
                .collect(Collectors.groupingBy(patient, LinkedHashMap::new, Collectors.toList()));
    }

    private static LocalDate date(Date date) {
        return date == null ? null : date.toLocalDate();
    }

    private static Instant instant(Timestamp horodatage) {
        return horodatage == null ? null : horodatage.toInstant();
    }
}
