# Getters anglais de l'ophtalmologie : réécriture par expressions (l'ordre des mots change).
import re
EXPRESSIONS = [
 ('subjectiveRefractometry', 'refractionSubjective'), ('objectiveRefractometry', 'refractionObjective'),
 ('correctionScopeOne', 'correctionPortee1'), ('correctionScopeTwo', 'correctionPortee2'),
 ('correctionScopeThree', 'correctionPortee3'), ('keratometry', 'keratometrie'), ('pachymetryIopCorrected', 'pioAirCorrigee'),
 ('pachymetryIopMeasured', 'pioAirMesuree'), ('pachymetryIop', 'pachymetrie'), ('pachymetry', 'pachymetrie'),
 ('tonometryIopMeasured', 'pioContactMesuree'), ('tonometry', 'pioContact'), ('slitLamp', 'lampeAFente'),
 ('examConditions', 'conditionsExamen'), ('NearVisualAcuity', 'AcuitePres'), ('VisualAcuity', 'AcuiteLoin'),
 ('AxisAvg', 'AxeMoyen'), ('KAvgDAvg', 'KmoyDmoy'), ('Cylinder', 'Cylindre'), ('Comments', 'Commentaires'),
]
OEIL = [('BothEyes', 'DeuxYeux'), ('LeftEye', 'OeilGauche'), ('RightEye', 'OeilDroit')]
LUNETTES = {'Addition': 'addition', 'Axis': 'axe', 'Bitoric': 'bitorique', 'Comment': 'commentaire', 'Cylinder': 'cylindre',
            'Diameter': 'diametre', 'Manufacturer': 'fabricant', 'Option': 'option', 'PrismAngle': 'prismeAngle',
            'PrismBase': 'prismeBase', 'PrismPower': 'prismePuissance', 'PrismType': 'prismeType', 'R1Radius': 'rayonR1',
            'R2Radius': 'rayonR2', 'RenewalFrequency': 'frequenceRenouvellement', 'SpecialGlassType': 'verresSpeciaux',
            'Sphere': 'sphere', 'Type': 'type'}

def nom_ophtalmo(k):
    m = re.match(r'^(left|right)(.+)$', k)
    if m and m.group(2) in LUNETTES:
        return LUNETTES[m.group(2)] + ('OeilGauche' if m.group(1) == 'left' else 'OeilDroit')
    if not re.match(r'^(subjective|objective|correctionScope|keratometry|pachymetry|tonometry|slitLamp|examConditions)', k):
        return None
    cyclo = k.endswith('Cycloplegia')
    if cyclo: k = k[:-len('Cycloplegia')]
    oeil = ''
    for a, f in OEIL:
        if a in k: k = k.replace(a, ''); oeil = f
    for a, f in EXPRESSIONS:
        k = k.replace(a, f)
    return k + oeil + ('SousCycloplegie' if cyclo else '')
