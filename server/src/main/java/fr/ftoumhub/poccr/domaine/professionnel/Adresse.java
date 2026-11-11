package fr.ftoumhub.poccr.domaine.professionnel;

import java.util.Objects;
import java.util.UUID;

/**
 * Adresse d'un professionnel de santé ou d'un établissement (son propriétaire), au plus une par type.
 *
 * @param proprietaireId identifiant du professionnel ou de l'établissement
 */
public record Adresse(UUID id,
                      UUID proprietaireId,
                      TypeAdresse type,
                      String ligne1,
                      String ligne2,
                      String ligne3,
                      String codePostal,
                      String ville,
                      String pays) {

    public Adresse {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(proprietaireId, "proprietaireId");
        Objects.requireNonNull(type, "type");
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private UUID id;
        private UUID proprietaireId;
        private TypeAdresse type;
        private String ligne1;
        private String ligne2;
        private String ligne3;
        private String codePostal;
        private String ville;
        private String pays;

        private Builder() {
        }

        public Builder id(UUID id) { this.id = id; return this; }
        public Builder proprietaireId(UUID proprietaireId) { this.proprietaireId = proprietaireId; return this; }
        public Builder type(TypeAdresse type) { this.type = type; return this; }
        public Builder ligne1(String ligne1) { this.ligne1 = ligne1; return this; }
        public Builder ligne2(String ligne2) { this.ligne2 = ligne2; return this; }
        public Builder ligne3(String ligne3) { this.ligne3 = ligne3; return this; }
        public Builder codePostal(String codePostal) { this.codePostal = codePostal; return this; }
        public Builder ville(String ville) { this.ville = ville; return this; }
        public Builder pays(String pays) { this.pays = pays; return this; }

        public Adresse construire() {
            return new Adresse(id, proprietaireId, type, ligne1, ligne2, ligne3, codePostal, ville, pays);
        }
    }
}
