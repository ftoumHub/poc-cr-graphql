-- Données entièrement fictives. Les dates sont relatives à current_date
-- pour que les filtres « depuis: P3M » renvoient toujours quelque chose.

insert into patient (id, ipp, nom, prenom, date_naissance, sexe) values
 ('11111111-1111-1111-1111-111111111111', '100001', 'MARTIN',  'Claire', date '1968-04-12', 'FEMININ'),
 ('22222222-2222-2222-2222-222222222222', '100002', 'MARTINEZ','Hugo',   date '1955-11-03', 'MASCULIN'),
 ('33333333-3333-3333-3333-333333333333', '100003', 'BERNARD', 'Lina',   date '1990-07-25', 'FEMININ');

insert into observation (id, patient_id, categorie, code, libelle, valeur, unite, date_observation) values
 (gen_random_uuid(), '11111111-1111-1111-1111-111111111111', 'BIOLOGIE',  'HBA1C', 'Hémoglobine glyquée', '7.2', '%',      current_date - 20),
 (gen_random_uuid(), '11111111-1111-1111-1111-111111111111', 'BIOLOGIE',  'HBA1C', 'Hémoglobine glyquée', '7.9', '%',      current_date - 200),
 (gen_random_uuid(), '11111111-1111-1111-1111-111111111111', 'BIOLOGIE',  'CREAT', 'Créatinine',          '82',  'µmol/L', current_date - 20),
 (gen_random_uuid(), '11111111-1111-1111-1111-111111111111', 'BIOLOGIE',  'LDL',   'LDL cholestérol',     '1.1', 'g/L',    current_date - 45),
 (gen_random_uuid(), '11111111-1111-1111-1111-111111111111', 'CONSTANTE', 'PAS',   'Pression systolique', '138', 'mmHg',   current_date - 2),
 (gen_random_uuid(), '11111111-1111-1111-1111-111111111111', 'CONSTANTE', 'POIDS', 'Poids',               '71',  'kg',     current_date - 2),
 (gen_random_uuid(), '22222222-2222-2222-2222-222222222222', 'BIOLOGIE',  'INR',   'INR',                 '2.6', null,     current_date - 7),
 (gen_random_uuid(), '22222222-2222-2222-2222-222222222222', 'BIOLOGIE',  'INR',   'INR',                 '3.4', null,     current_date - 37),
 (gen_random_uuid(), '22222222-2222-2222-2222-222222222222', 'CONSTANTE', 'FC',    'Fréquence cardiaque', '64',  'bpm',    current_date - 1),
 (gen_random_uuid(), '33333333-3333-3333-3333-333333333333', 'BIOLOGIE',  'TSH',   'TSH',                 '1.8', 'mUI/L',  current_date - 60);

insert into traitement (id, patient_id, molecule, posologie, date_debut, date_fin) values
 (gen_random_uuid(), '11111111-1111-1111-1111-111111111111', 'Metformine 1000 mg',   '1 cp matin et soir',  current_date - 900, null),
 (gen_random_uuid(), '11111111-1111-1111-1111-111111111111', 'Atorvastatine 20 mg',  '1 cp le soir',        current_date - 400, null),
 (gen_random_uuid(), '11111111-1111-1111-1111-111111111111', 'Amoxicilline 1 g',     '1 g x 3 / j, 7 jours', current_date - 120, current_date - 113),
 (gen_random_uuid(), '22222222-2222-2222-2222-222222222222', 'Warfarine 5 mg',       'selon INR',           current_date - 1500, null),
 (gen_random_uuid(), '33333333-3333-3333-3333-333333333333', 'Lévothyroxine 75 µg', '1 cp à jeun',         current_date - 300, null);
