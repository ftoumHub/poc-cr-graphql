package fr.ftoumhub.poccr.domaine.port;

import fr.ftoumhub.poccr.domaine.etablissement.Etablissement;
import fr.ftoumhub.poccr.domaine.organisation.Service;
import fr.ftoumhub.poccr.domaine.sejour.UniteFonctionnelle;

import java.util.List;
import java.util.Optional;

/** Port secondaire : organisation des établissements (établissement > service > unité fonctionnelle). */
public interface Organisation {

    List<Etablissement> etablissements();

    List<Service> services();

    /** Unités fonctionnelles avec leur service. */
    List<UniteFonctionnelle> unitesFonctionnelles();

    default Optional<UniteFonctionnelle> uniteFonctionnelle(String code) {
        return unitesFonctionnelles().stream().filter(u -> u.code().equals(code)).findFirst();
    }

    default Optional<Service> service(String code) {
        return services().stream().filter(s -> s.code().equals(code)).findFirst();
    }
}
