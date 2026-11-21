package fr.ftoumhub.poccr.domaine.dossier;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Instance d'un formulaire dynamique : valeurs saisies par code de rubrique.
 *
 * @param modele        code du modèle (identifiant d'origine, ex. « 9115405773860914995 »)
 * @param libelleModele titre du modèle
 * @param valeurs       code de rubrique → valeur saisie ; une rubrique non saisie est absente
 */
public record Formulaire(UUID id, UUID patientId, String modele, String libelleModele, Instant creeLe,
                         Instant modifieLe, String auteur, Map<String, String> valeurs) {

    public Formulaire {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(patientId, "patientId");
        Objects.requireNonNull(modele, "modele");
        Objects.requireNonNull(creeLe, "creeLe");
        valeurs = valeurs == null ? Map.of() : Map.copyOf(valeurs);
    }

    public Optional<String> rubrique(String code) {
        return Optional.ofNullable(valeurs.get(code));
    }

    public boolean contient(String code) {
        return valeurs.containsKey(code);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private UUID id;
        private UUID patientId;
        private String modele;
        private String libelleModele;
        private Instant creeLe;
        private Instant modifieLe;
        private String auteur;
        private Map<String, String> valeurs;

        private Builder() {
        }

        public Builder id(UUID id) { this.id = id; return this; }
        public Builder patientId(UUID patientId) { this.patientId = patientId; return this; }
        public Builder modele(String modele) { this.modele = modele; return this; }
        public Builder libelleModele(String libelleModele) { this.libelleModele = libelleModele; return this; }
        public Builder creeLe(Instant creeLe) { this.creeLe = creeLe; return this; }
        public Builder modifieLe(Instant modifieLe) { this.modifieLe = modifieLe; return this; }
        public Builder auteur(String auteur) { this.auteur = auteur; return this; }
        public Builder valeurs(Map<String, String> valeurs) { this.valeurs = valeurs; return this; }

        public Formulaire construire() {
            return new Formulaire(id, patientId, modele, libelleModele, creeLe, modifieLe, auteur, valeurs);
        }
    }
}
