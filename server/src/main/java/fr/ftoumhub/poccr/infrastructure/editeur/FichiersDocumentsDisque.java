package fr.ftoumhub.poccr.infrastructure.editeur;

import fr.ftoumhub.poccr.domaine.port.FichiersDocuments;
import fr.ftoumhub.poccr.domaine.validation.NomDocument;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Port FichiersDocuments sur le stockage disque existant. */
public class FichiersDocumentsDisque implements FichiersDocuments {

    private final StockageDocuments stockage;

    public FichiersDocumentsDisque(StockageDocuments stockage) {
        this.stockage = stockage;
    }

    @Override
    public List<NomDocument> lister() {
        return stockage.lister().stream().map(NomDocument::new).toList();
    }

    @Override
    public void creerVierge(NomDocument nom) {
        stockage.creerVierge(nom.valeur());
    }

    @Override
    public void renommer(NomDocument ancien, NomDocument nouveau) {
        stockage.renommer(ancien.valeur(), nouveau.valeur());
    }

    @Override
    public void dupliquer(NomDocument source, NomDocument copie) {
        stockage.dupliquer(source.valeur(), copie.valeur());
    }

    @Override
    public Instant derniereModification(NomDocument nom) {
        return stockage.derniereModification(nom.valeur());
    }

    @Override
    public Optional<UUID> patientLie(NomDocument nom) {
        return stockage.patientLie(nom.valeur());
    }
}
