package fr.ftoumhub.poccr.domaine.sejour;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.Comparator;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Venue d'un patient dans l'établissement.
 *
 * @param numero               numéro de venue (NDA)
 * @param dateSortie           null tant que le séjour est en cours (ou non encore commencé, s'il est programmé)
 * @param typeDocument         type du compte rendu rédigé pour ce séjour (CRH, CRO…) ; codage PMSI, facultatif
 * @param diagnosticCim10Code  diagnostic principal, code CIM-10 ; facultatif
 * @param diagnosticCim10Libelle libellé du diagnostic principal ; facultatif
 * @param acteCcamCode         acte principal, code CCAM ; facultatif
 * @param acteCcamLibelle      libellé de l'acte principal ; facultatif
 * @param ufMedicale           unité fonctionnelle médicale courante ; facultative
 * @param ufHebergement        unité fonctionnelle d'hébergement courante ; facultative
 * @param medecinAdresseurId   professionnel qui a adressé le patient ; facultatif
 */
public record Sejour(UUID id,
                     UUID patientId,
                     String numero,
                     TypeSejour type,
                     LocalDate dateEntree,
                     LocalDate dateSortie,
                     String service,
                     String motif,
                     String medecinResponsable,
                     TypeDocument typeDocument,
                     String diagnosticCim10Code,
                     String diagnosticCim10Libelle,
                     String acteCcamCode,
                     String acteCcamLibelle,
                     UniteFonctionnelle ufMedicale,
                     UniteFonctionnelle ufHebergement,
                     UUID medecinAdresseurId) {

    public Sejour {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(patientId, "patientId");
        Objects.requireNonNull(numero, "numero");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(dateEntree, "dateEntree");
        Objects.requireNonNull(service, "service");
        if (dateSortie != null && dateSortie.isBefore(dateEntree)) {
            throw new IllegalArgumentException("Sortie avant l'entrée pour le séjour " + numero);
        }
    }

    public boolean estProgrammeApres(LocalDate date) {
        return dateEntree.isAfter(date);
    }

    public boolean estEnCoursLe(LocalDate date) {
        return !estProgrammeApres(date) && (dateSortie == null || !date.isAfter(dateSortie));
    }

    /** Durée en jours (nuits) ; un séjour en cours est compté jusqu'à la date donnée. Ambulatoire = 0. */
    public long dureeEnJoursLe(LocalDate date) {
        LocalDate fin = dateSortie != null ? dateSortie : date;
        return Math.max(0, ChronoUnit.DAYS.between(dateEntree, fin));
    }

    /**
     * Séjour auquel un compte rendu se rapporte par défaut :
     * le séjour en cours (le plus récemment entré s'il y en a plusieurs),
     * sinon le dernier séjour terminé. Les séjours programmés sont ignorés.
     */
    /**
     * Séjour de référence d'un compte rendu rattaché à un séjour (clé de contexte) : ce séjour, s'il fait
     * partie des séjours du patient ; sinon la règle générale. Le compte rendu ne bascule donc pas sur une
     * nouvelle venue avant sa validation.
     */
    public static Optional<Sejour> deReference(Collection<Sejour> sejours, LocalDate date, UUID sejourDuDocument) {
        if (sejourDuDocument != null) {
            Optional<Sejour> fige = sejours.stream().filter(s -> s.id().equals(sejourDuDocument)).findFirst();
            if (fige.isPresent()) {
                return fige;
            }
        }
        return deReference(sejours, date);
    }

    public static Optional<Sejour> deReference(Collection<Sejour> sejours, LocalDate date) {
        Comparator<Sejour> parEntree = Comparator.comparing(Sejour::dateEntree);
        Optional<Sejour> enCours = sejours.stream()
                .filter(s -> s.estEnCoursLe(date))
                .max(parEntree);
        if (enCours.isPresent()) {
            return enCours;
        }
        return sejours.stream()
                .filter(s -> !s.estProgrammeApres(date))
                .max(Comparator.comparing((Sejour s) -> s.dateSortie() != null ? s.dateSortie() : s.dateEntree())
                        .thenComparing(parEntree));
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private UUID id;
        private UUID patientId;
        private String numero;
        private TypeSejour type;
        private LocalDate dateEntree;
        private LocalDate dateSortie;
        private String service;
        private String motif;
        private String medecinResponsable;
        private TypeDocument typeDocument;
        private String diagnosticCim10Code;
        private String diagnosticCim10Libelle;
        private String acteCcamCode;
        private String acteCcamLibelle;
        private UniteFonctionnelle ufMedicale;
        private UniteFonctionnelle ufHebergement;
        private UUID medecinAdresseurId;

        private Builder() {
        }

        public Builder id(UUID id) { this.id = id; return this; }
        public Builder patientId(UUID patientId) { this.patientId = patientId; return this; }
        public Builder numero(String numero) { this.numero = numero; return this; }
        public Builder type(TypeSejour type) { this.type = type; return this; }
        public Builder dateEntree(LocalDate dateEntree) { this.dateEntree = dateEntree; return this; }
        public Builder dateSortie(LocalDate dateSortie) { this.dateSortie = dateSortie; return this; }
        public Builder service(String service) { this.service = service; return this; }
        public Builder motif(String motif) { this.motif = motif; return this; }
        public Builder medecinResponsable(String medecinResponsable) { this.medecinResponsable = medecinResponsable; return this; }
        public Builder typeDocument(TypeDocument typeDocument) { this.typeDocument = typeDocument; return this; }
        public Builder diagnosticCim10Code(String diagnosticCim10Code) { this.diagnosticCim10Code = diagnosticCim10Code; return this; }
        public Builder diagnosticCim10Libelle(String diagnosticCim10Libelle) { this.diagnosticCim10Libelle = diagnosticCim10Libelle; return this; }
        public Builder acteCcamCode(String acteCcamCode) { this.acteCcamCode = acteCcamCode; return this; }
        public Builder acteCcamLibelle(String acteCcamLibelle) { this.acteCcamLibelle = acteCcamLibelle; return this; }
        public Builder ufMedicale(UniteFonctionnelle ufMedicale) { this.ufMedicale = ufMedicale; return this; }
        public Builder ufHebergement(UniteFonctionnelle ufHebergement) { this.ufHebergement = ufHebergement; return this; }
        public Builder medecinAdresseurId(UUID medecinAdresseurId) { this.medecinAdresseurId = medecinAdresseurId; return this; }

        public Sejour construire() {
            return new Sejour(id, patientId, numero, type, dateEntree, dateSortie, service, motif, medecinResponsable,
                    typeDocument, diagnosticCim10Code, diagnosticCim10Libelle, acteCcamCode, acteCcamLibelle,
                    ufMedicale, ufHebergement, medecinAdresseurId);
        }
    }
}
