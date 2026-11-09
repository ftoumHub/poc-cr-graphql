package fr.ftoumhub.poccr.domaine.sejour;

/**
 * Type du compte rendu rédigé pour un séjour. Vocabulaire fermé, repris du corpus PARHAF
 * (projet PARTAGES, Health Data Hub) : voir {@code docs/} dans le projet de référence
 * {@code parhaf-fhir-loader} (DocumentTypeCatalog).
 *
 * Rattaché à {@code Sejour} par simplicité pour ce POC : un séjour ne porte qu'un seul type
 * de compte rendu ici, alors que PARHAF en autorise plusieurs par patient (ex. un CRH et un
 * ANAPATH pour le même séjour). Le jour où un document est modélisé en base (voir
 * CLAUDE.md « rattacher le document à un séjour »), ce champ migrera vers lui.
 */
public enum TypeDocument {
    CRH,
    CRC,
    CRO,
    ANAPATH,
    URGENCES,
    ACCOUCHEMENT,
    MATERNITE
}
