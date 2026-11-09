create table sejour (
    id                  uuid primary key,
    patient_id          uuid         not null references patient (id),
    numero              varchar(20)  not null unique,  -- numéro de venue / NDA
    type                varchar(30)  not null,
    date_entree         date         not null,
    date_sortie         date,                          -- null = en cours
    service             varchar(100) not null,
    motif               varchar(300),
    medecin_responsable varchar(100)
);
create index idx_sejour_patient on sejour (patient_id, date_entree desc);

-- Données fictives, relatives à current_date.
insert into sejour (id, patient_id, numero, type, date_entree, date_sortie, service, motif, medecin_responsable) values
 (gen_random_uuid(), '11111111-1111-1111-1111-111111111111', 'V2026000101', 'HOSPITALISATION_COMPLETE',
    current_date - 3,   null,               'Endocrinologie', 'Déséquilibre glycémique', 'Dr DURAND'),
 (gen_random_uuid(), '11111111-1111-1111-1111-111111111111', 'V2026000042', 'HOSPITALISATION_COMPLETE',
    current_date - 125, current_date - 120, 'Maladies infectieuses', 'Pneumopathie communautaire', 'Dr LEROY'),
 (gen_random_uuid(), '22222222-2222-2222-2222-222222222222', 'V2026000077', 'CONSULTATION',
    current_date - 7,   current_date - 7,   'Cardiologie', 'Suivi anticoagulation', 'Dr PETIT'),
 (gen_random_uuid(), '22222222-2222-2222-2222-222222222222', 'V2026000150', 'HOSPITALISATION_JOUR',
    current_date + 10,  null,               'Cardiologie', 'Échographie transœsophagienne programmée', 'Dr PETIT');
-- Patiente 3 (BERNARD) : aucun séjour, pour tester l'absence.
