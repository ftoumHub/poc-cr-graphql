"""Tests du convertisseur (sans serveur) : python3 -m unittest discover -s outils/velocity -p 'test_*.py'"""
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from velocity import lire_instruction, lire_reference, lire_condition  # noqa: E402
from correspondance import (Catalogue, Chemin, Segment, corriger, lire_chemin_graphql, norm,  # noqa: E402
                            famille_formulaire, FAMILLE_BIOLOGIE)
from convertir import condition_json, reference, _chapitre, _role_medecin  # noqa: E402


class LectureVelocity(unittest.TestCase):

    def test_une_valeur_et_une_boucle_avec_son_indication_de_chemin(self):
        valeur = lire_instruction('MERGEFIELD 4iV.val:$C0PATI.pati_nom_usu \\* MERGEFORMAT')
        self.assertEqual((valeur.nature, valeur.corps), ('val', '$C0PATI.pati_nom_usu'))
        boucle = lire_instruction('MERGEFIELD 4iV.cmd: FNCT:SIGN:DEB BCL31:SignatairesCHEMTECH:.getListSignatairesSecondaires()*'
                                  '.ide_medecin.mede_nom_usu#foreach ($CREC31 in $C0SIGN.getListSignatairesSecondaires()) \\* MERGEFORMAT')
        self.assertEqual((boucle.directive, boucle.variable, boucle.source, boucle.fonctionnalite),
                         ('foreach', 'CREC31', '$C0SIGN.getListSignatairesSecondaires()', 'SIGN'))
        self.assertTrue(boucle.chemin_technique.startswith('.getListSignatairesSecondaires()*'))

    def test_une_directive_ecrite_avec_un_dollar_est_reconnue_et_signalee(self):
        ins = lire_instruction('MERGEFIELD 4iW.cmd:FIN BCL29: #$end \\* MERGEFORMAT')
        self.assertEqual(ins.directive, 'end')
        self.assertTrue(ins.ecriture_douteuse)

    def test_un_autre_champ_word_n_est_pas_une_instruction(self):
        self.assertIsNone(lire_instruction('FORMTEXT'))

    def test_une_reference_garde_ses_arguments_litteraux(self):
        ref = lire_reference('$C0PATI.getIde_adresse_patient("ADR_PERSO").adre_ligne1')
        self.assertEqual(ref.variable, 'C0PATI')
        self.assertEqual(ref.chemin(), '.getIde_adresse_patient("ADR_PERSO").adre_ligne1')

    def test_les_conditions_comparaisons_negations_et_disjonctions(self):
        self.assertEqual(lire_condition('$C0PATI.pati_sexe=="F"')[0:2], ('cmp', '=='))
        self.assertEqual(lire_condition('!$CREC4.empa_dat_fin_val')[0], 'non')
        ou = lire_condition("$C0DOCUMENT.getCategoriePsCreateur()=='Med.associé/Interne' or "
                            "$C0DOCUMENT.getCategoriePsCreateur()=='Infirmier(ère)'")
        self.assertEqual(ou[0], 'ou')
        self.assertEqual(ou[1][1][3], ('lit', 'Infirmier(ère)'))
        with self.assertRaises(ValueError):
            lire_condition('$X.a == == "b"')


class Correspondances(unittest.TestCase):

    def test_la_normalisation_ignore_get_la_casse_les_etoiles_et_les_guillemets(self):
        self.assertEqual(norm(".getListSign()*.ide_medecin.getMede_prenom()"), norm('.listSign.ide_medecin.mede_prenom'))
        self.assertEqual(norm(".getRubriqueValue('X')"), 'rubriquevalue("X")')

    def test_un_chemin_du_contrat_se_lit_avec_ses_arguments_et_ses_listes(self):
        s = lire_chemin_graphql('signataires[].medecin.adresse(type: PROFESSIONNELLE).telephone(type: FIXE).numero')
        self.assertEqual([x.nom for x in s], ['signataires', 'medecin', 'adresse', 'telephone', 'numero'])
        self.assertTrue(s[0].liste)
        self.assertEqual(s[2].args, (('type', 'PROFESSIONNELLE'),))

    def test_les_corrections_ramenent_le_contrat_cdf_au_schema_du_poc(self):
        self.assertEqual(corriger('nomUsuel'), 'nom')
        self.assertEqual(corriger('signatairePrincipal.medecin.titreCivilite.libelleEdition'), 'signatairePrincipal.medecin.titre')
        self.assertEqual(corriger('prescriptions(nature: MEDICAMENTS, statut: EN_COURS)[].lignes[].posologieLongue'),
                         'prescriptions(nature: MEDICAMENTS, statut: EN_COURS)[].posologie')

    def test_le_catalogue_reunit_les_racines_fusionnees_du_patient(self):
        catalogue = Catalogue([{'FONCTIONNALITE': 'FUSPATI', 'CHEMIN_TECHNIQUE': '.getTitre()', 'CHEMIN_GRAPHQL': 'titre'}])
        self.assertEqual(catalogue.chercher('PATI', '.getTitre()')['CHEMIN_GRAPHQL'], 'titre')
        self.assertEqual(catalogue.chercher('FUSPATI', '.getSadDiagAnteceds()*.sad_diag.adia_libelle')['CHEMIN_GRAPHQL'],
                         'antecedents[].libelle')

    def test_une_rubrique_de_formulaire_de_contexte_devient_un_argument(self):
        r = famille_formulaire('RUCVSPEC1').traduire(None, '.getRubriqueValue("RUCVNLOT1")')
        self.assertEqual([s.nom for s in r.segments], ['formulaires', 'rubrique'])
        self.assertEqual(r.segments[1].args, (('code', 'RUCVNLOT1'),))

    def test_les_niveaux_de_resultats_de_biologie_sont_ramenes_a_une_observation(self):
        self.assertEqual(FAMILLE_BIOLOGIE.traduire(None, '.getLibelleFamille()').segments[-1].nom, 'categorie')
        self.assertEqual(FAMILLE_BIOLOGIE.traduire(None, '.getResultatsComplexes()*.getResultatsUnitaires()*.getNom()')
                         .segments[-1].nom, 'code')


class Emission(unittest.TestCase):
    signataires = Chemin('document', [Segment('signatairesSecondaires', (), True)])

    def test_une_reference_est_relative_a_la_boucle_la_plus_proche_sinon_absolue(self):
        nom = Chemin('document', [Segment('signatairesSecondaires', (), True), Segment('medecin'), Segment('nom')])
        self.assertEqual(reference(nom, [self.signataires]), {'c': 'medecin.nom'})
        patient = Chemin('patient', [Segment('adresse', (('type', 'PERSONNELLE'),)), Segment('ville')])
        self.assertEqual(reference(patient, [self.signataires]),
                         {'r': 'patient', 'c': 'adresse.ville', 'a': {'0': {'type': 'PERSONNELLE'}}})

    def test_une_condition_vide_ou_comparee_devient_un_noeud_si(self):
        sexe = Chemin('patient', [Segment('sexe')])
        self.assertEqual(condition_json(('cmp', '==', sexe, 'FEMININ'), []), {'r': 'patient', 'c': 'sexe', 'op': '=', 'v': 'FEMININ'})
        self.assertEqual(condition_json(('vide', sexe), []), {'r': 'patient', 'c': 'sexe', 'op': 'vide'})


class Contexte(unittest.TestCase):

    def test_le_titre_d_observations_donne_le_chapitre_et_le_nombre(self):
        self.assertEqual(_chapitre(['OBSERVATIONS – IDE (5 dernières)', ''], 2), ('IDE', 5))
        self.assertEqual(_chapitre(['Suivi Social', '▪ - :'], 2), ('Suivi Social', None))

    def test_le_role_du_professionnel_privilegie_le_libelle_de_sa_ligne(self):
        self.assertEqual(_role_medecin(['- Médecin traitant : …', '- Médecin adresseur : '], 1), 'ADRESSEUR')
        self.assertEqual(_role_medecin(['Médecin traitant : '], 0), 'TRAITANT')
        self.assertIsNone(_role_medecin(['Identification du prescripteur'], 0))


if __name__ == '__main__':
    unittest.main()
