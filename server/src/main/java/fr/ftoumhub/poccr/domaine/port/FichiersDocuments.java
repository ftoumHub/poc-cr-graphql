package fr.ftoumhub.poccr.domaine.port;

import fr.ftoumhub.poccr.domaine.validation.NomDocument;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Port secondaire : contenu .docx des documents, rangé sous leur nom. */
public interface FichiersDocuments {

    List<NomDocument> lister();

    void creerVierge(NomDocument nom);

    void renommer(NomDocument ancien, NomDocument nouveau);

    void dupliquer(NomDocument source, NomDocument copie);

    Instant derniereModification(NomDocument nom);

    /** Patient de la première liaison trouvée dans le fichier (lecture au mieux). */
    Optional<UUID> patientLie(NomDocument nom);
}
