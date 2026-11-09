-- Jeu de données complémentaire, entièrement fictif. Dates relatives à current_date.
--
--  100004 DUPONT Camille    sexe INCONNU      -> formes neutres des accords (« né(e) »), séjour aux urgences en cours
--  100005 MOREAU Alex       sexe INDETERMINE  -> formes neutres, séjour terminé, un seul traitement
--  100006 LEFEBVRE Jean     polypathologique  -> volume : ~240 observations, 12 traitements, 6 séjours
--  2000xx TEST Patient01-20 lot de 20 patients -> recherche « TEST », regroupement des DataLoaders

-- ---------------------------------------------------------------------------------------------
-- Sexe inconnu / indéterminé
-- ---------------------------------------------------------------------------------------------
insert into patient (id, ipp, nom, prenom, date_naissance, sexe) values
 ('44444444-4444-4444-4444-444444444444', '100004', 'DUPONT',   'Camille', date '1975-02-14', 'INCONNU'),
 ('55555555-5555-5555-5555-555555555555', '100005', 'MOREAU',   'Alex',    date '2001-09-30', 'INDETERMINE'),
 ('66666666-6666-6666-6666-666666666666', '100006', 'LEFEBVRE', 'Jean',    date '1942-06-18', 'MASCULIN');

insert into observation (id, patient_id, categorie, code, libelle, valeur, unite, date_observation) values
 (gen_random_uuid(), '44444444-4444-4444-4444-444444444444', 'BIOLOGIE',  'CRP',  'Protéine C réactive',    '48',   'mg/L',  current_date - 1),
 (gen_random_uuid(), '44444444-4444-4444-4444-444444444444', 'BIOLOGIE',  'LEUCO','Leucocytes',             '13.2', 'G/L',   current_date - 1),
 (gen_random_uuid(), '44444444-4444-4444-4444-444444444444', 'CONSTANTE', 'TEMP', 'Température',            '38.6', '°C',    current_date),
 (gen_random_uuid(), '44444444-4444-4444-4444-444444444444', 'CONSTANTE', 'FC',   'Fréquence cardiaque',    '102',  'bpm',   current_date),
 (gen_random_uuid(), '55555555-5555-5555-5555-555555555555', 'BIOLOGIE',  'FERR', 'Ferritine',              '12',   'µg/L',  current_date - 40),
 (gen_random_uuid(), '55555555-5555-5555-5555-555555555555', 'BIOLOGIE',  'HB',   'Hémoglobine',            '11.1', 'g/dL',  current_date - 40);

insert into traitement (id, patient_id, molecule, posologie, date_debut, date_fin) values
 (gen_random_uuid(), '44444444-4444-4444-4444-444444444444', 'Paracétamol 1 g',      '1 g x 4 / j si douleur', current_date - 1, null),
 (gen_random_uuid(), '44444444-4444-4444-4444-444444444444', 'Ceftriaxone 1 g IV',   '1 g / j',                current_date - 1, null),
 (gen_random_uuid(), '55555555-5555-5555-5555-555555555555', 'Fer ferreux 80 mg',    '1 cp le matin',          current_date - 38, null);

insert into sejour (id, patient_id, numero, type, date_entree, date_sortie, service, motif, medecin_responsable) values
 (gen_random_uuid(), '44444444-4444-4444-4444-444444444444', 'V2026000201', 'URGENCES',
    current_date - 1, null, 'Urgences adultes', 'Fièvre et syndrome inflammatoire', 'Dr GARNIER'),
 (gen_random_uuid(), '55555555-5555-5555-5555-555555555555', 'V2026000180', 'HOSPITALISATION_JOUR',
    current_date - 40, current_date - 40, 'Hématologie', 'Bilan d''anémie', 'Dr ROUX');

-- ---------------------------------------------------------------------------------------------
-- Patient polypathologique : volume pour les tableaux longs et les filtres
-- ---------------------------------------------------------------------------------------------

-- Biologie hebdomadaire sur un an : 4 examens x 52 semaines = 208 lignes.
insert into observation (id, patient_id, categorie, code, libelle, valeur, unite, date_observation)
select gen_random_uuid(), '66666666-6666-6666-6666-666666666666', 'BIOLOGIE', e.code, e.libelle,
       round((e.base + e.amplitude * sin(s.semaine * e.phase))::numeric, e.decimales)::text,
       e.unite, current_date - (s.semaine * 7 + 2)
from generate_series(0, 51) as s(semaine)
cross join (values
    ('CREAT', 'Créatinine',  140.0, 25.0, 0.7, 0, 'µmol/L'),
    ('K',     'Potassium',     4.6,  0.5, 1.3, 1, 'mmol/L'),
    ('NA',    'Sodium',      138.0,  3.0, 0.9, 0, 'mmol/L'),
    ('HB',    'Hémoglobine',  11.8,  1.2, 0.4, 1, 'g/dL')
) as e(code, libelle, base, amplitude, phase, decimales, unite);

-- Constantes quotidiennes pendant le séjour en cours : 3 mesures x 10 jours = 30 lignes.
insert into observation (id, patient_id, categorie, code, libelle, valeur, unite, date_observation)
select gen_random_uuid(), '66666666-6666-6666-6666-666666666666', 'CONSTANTE', c.code, c.libelle,
       round((c.base + c.amplitude * cos(j.jour * c.phase))::numeric, c.decimales)::text,
       c.unite, current_date - j.jour
from generate_series(0, 9) as j(jour)
cross join (values
    ('PAS',  'Pression systolique', 135.0, 15.0, 0.8, 0, 'mmHg'),
    ('FC',   'Fréquence cardiaque',  78.0,  8.0, 1.1, 0, 'bpm'),
    ('SPO2', 'Saturation en O2',     94.0,  2.0, 0.6, 0, '%')
) as c(code, libelle, base, amplitude, phase, decimales, unite);

-- 12 traitements : 8 en cours, 4 terminés.
insert into traitement (id, patient_id, molecule, posologie, date_debut, date_fin) values
 (gen_random_uuid(), '66666666-6666-6666-6666-666666666666', 'Furosémide 40 mg',        '1 cp le matin',           current_date - 700, null),
 (gen_random_uuid(), '66666666-6666-6666-6666-666666666666', 'Bisoprolol 2,5 mg',       '1 cp le matin',           current_date - 650, null),
 (gen_random_uuid(), '66666666-6666-6666-6666-666666666666', 'Ramipril 5 mg',           '1 cp le soir',            current_date - 600, null),
 (gen_random_uuid(), '66666666-6666-6666-6666-666666666666', 'Apixaban 2,5 mg',         '1 cp matin et soir',      current_date - 500, null),
 (gen_random_uuid(), '66666666-6666-6666-6666-666666666666', 'Insuline glargine',       '18 UI le soir',           current_date - 400, null),
 (gen_random_uuid(), '66666666-6666-6666-6666-666666666666', 'Allopurinol 100 mg',      '1 cp / j',                current_date - 300, null),
 (gen_random_uuid(), '66666666-6666-6666-6666-666666666666', 'Pantoprazole 20 mg',      '1 cp le matin',           current_date - 10,  null),
 (gen_random_uuid(), '66666666-6666-6666-6666-666666666666', 'Énoxaparine 4000 UI',     '1 injection / j',         current_date - 10,  null),
 (gen_random_uuid(), '66666666-6666-6666-6666-666666666666', 'Metformine 500 mg',       '1 cp matin et soir',      current_date - 1200, current_date - 401),
 (gen_random_uuid(), '66666666-6666-6666-6666-666666666666', 'Amlodipine 5 mg',         '1 cp / j',                current_date - 900, current_date - 601),
 (gen_random_uuid(), '66666666-6666-6666-6666-666666666666', 'Prednisone 20 mg',        'décroissance sur 10 j',   current_date - 210, current_date - 200),
 (gen_random_uuid(), '66666666-6666-6666-6666-666666666666', 'Amoxicilline-ac. clav.',  '1 g x 3 / j, 8 jours',    current_date - 95,  current_date - 87);

-- 6 séjours : 1 en cours, 5 terminés (dont une consultation ambulatoire).
insert into sejour (id, patient_id, numero, type, date_entree, date_sortie, service, motif, medecin_responsable) values
 (gen_random_uuid(), '66666666-6666-6666-6666-666666666666', 'V2026000190', 'HOSPITALISATION_COMPLETE',
    current_date - 10,  null,               'Cardiologie',        'Décompensation cardiaque',          'Dr PETIT'),
 (gen_random_uuid(), '66666666-6666-6666-6666-666666666666', 'V2026000120', 'HOSPITALISATION_COMPLETE',
    current_date - 98,  current_date - 87,  'Pneumologie',        'Pneumopathie d''inhalation',        'Dr FAURE'),
 (gen_random_uuid(), '66666666-6666-6666-6666-666666666666', 'V2026000060', 'HOSPITALISATION_COMPLETE',
    current_date - 212, current_date - 205, 'Médecine interne',   'Poussée de goutte polyarticulaire', 'Dr BLANC'),
 (gen_random_uuid(), '66666666-6666-6666-6666-666666666666', 'V2026000031', 'CONSULTATION',
    current_date - 260, current_date - 260, 'Néphrologie',        'Suivi insuffisance rénale',         'Dr MERCIER'),
 (gen_random_uuid(), '66666666-6666-6666-6666-666666666666', 'V2025000410', 'HOSPITALISATION_JOUR',
    current_date - 330, current_date - 330, 'Endocrinologie',     'Mise sous insuline',                'Dr DURAND'),
 (gen_random_uuid(), '66666666-6666-6666-6666-666666666666', 'V2025000222', 'URGENCES',
    current_date - 420, current_date - 419, 'Urgences adultes',   'Chute à domicile',                  'Dr GARNIER');

-- ---------------------------------------------------------------------------------------------
-- Lot de 20 patients TEST : recherche « TEST » et regroupement des DataLoaders
-- (une requête qui demande les observations des 20 patients ne doit faire qu'un SELECT).
-- Identifiants déterministes : md5('test-patient-NN')::uuid.
-- ---------------------------------------------------------------------------------------------
insert into patient (id, ipp, nom, prenom, date_naissance, sexe)
select md5('test-patient-' || lpad(n::text, 2, '0'))::uuid,
       '2000' || lpad(n::text, 2, '0'),
       'TEST',
       'Patient' || lpad(n::text, 2, '0'),
       date '1940-01-01' + (n * 1100),
       (array['FEMININ', 'MASCULIN', 'FEMININ', 'MASCULIN', 'INCONNU'])[1 + n % 5]
from generate_series(1, 20) as g(n);

insert into observation (id, patient_id, categorie, code, libelle, valeur, unite, date_observation)
select gen_random_uuid(), md5('test-patient-' || lpad(n::text, 2, '0'))::uuid, 'BIOLOGIE', e.code, e.libelle,
       round((e.base + e.pas * ((n * 7 + e.decalage) % 10))::numeric, 1)::text, e.unite,
       current_date - ((n * 11 + e.decalage) % 150)
from generate_series(1, 20) as g(n)
cross join (values
    ('HBA1C', 'Hémoglobine glyquée', 5.6, 0.4, 3, '%'),
    ('CREAT', 'Créatinine',          60,  9,   5, 'µmol/L'),
    ('TSH',   'TSH',                 0.8, 0.5, 8, 'mUI/L')
) as e(code, libelle, base, pas, decalage, unite);

insert into traitement (id, patient_id, molecule, posologie, date_debut, date_fin)
select gen_random_uuid(), md5('test-patient-' || lpad(n::text, 2, '0'))::uuid,
       (array['Paracétamol 1 g', 'Amlodipine 5 mg', 'Lévothyroxine 50 µg', 'Metformine 850 mg'])[1 + n % 4],
       '1 cp / j',
       current_date - (n * 20),
       case when n % 3 = 0 then current_date - (n * 2) end   -- un tiers terminés
from generate_series(1, 20) as g(n);

-- Un patient TEST sur deux a un séjour ; un sur quatre est encore hospitalisé.
insert into sejour (id, patient_id, numero, type, date_entree, date_sortie, service, motif, medecin_responsable)
select gen_random_uuid(), md5('test-patient-' || lpad(n::text, 2, '0'))::uuid,
       'VT' || lpad(n::text, 9, '0'),
       'HOSPITALISATION_COMPLETE',
       current_date - (n * 3),
       case when n % 4 = 0 then null else current_date - n end,
       (array['Médecine polyvalente', 'Gériatrie', 'Chirurgie viscérale'])[1 + n % 3],
       'Séjour de test n° ' || n,
       'Dr TEST'
from generate_series(2, 20, 2) as g(n);
