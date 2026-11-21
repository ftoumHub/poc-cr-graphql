-- Clé de contexte : établissement > service > unité fonctionnelle (organisation), patient > séjour (dossier),
-- professionnel (auteur). La page hôte navigue dans ce contexte ; un compte rendu le fige à sa création.
-- Conception : docs/conception/2026-11-21-contexte-navigation-document.md

-- --- Organisation ---------------------------------------------------------------------------------
insert into etablissement (id, finess, siret, libelle, email) values
 ('e0000000-0000-0000-0000-000000000002', '990000002', '99000000200016', 'Centre de réadaptation POC',
  'accueil@readaptation-poc.invalid');

insert into adresse (id, etablissement_id, type, ligne1, ligne2, ligne3, code_postal, ville, pays) values
 ('b0000000-0000-0000-0000-000000000010', 'e0000000-0000-0000-0000-000000000002', 'PRINCIPALE',
  '25 chemin des Tilleuls', null, null, '69340', 'Francheville', 'France');
insert into telephone (id, adresse_id, type, numero) values
 (gen_random_uuid(), 'b0000000-0000-0000-0000-000000000010', 'PRINCIPAL', '04 00 00 50 00'),
 (gen_random_uuid(), 'b0000000-0000-0000-0000-000000000010', 'FAX', '04 00 00 50 99');

create table service (
    code             varchar(20)  primary key,
    etablissement_id uuid         not null references etablissement (id),
    libelle          varchar(200) not null
);

insert into service (code, etablissement_id, libelle) values
 ('URG',     'e0000000-0000-0000-0000-000000000001', 'Urgences'),
 ('CARDIO',  'e0000000-0000-0000-0000-000000000001', 'Cardiologie'),
 ('ENDOC',   'e0000000-0000-0000-0000-000000000001', 'Endocrinologie'),
 ('INFECT',  'e0000000-0000-0000-0000-000000000001', 'Maladies infectieuses'),
 ('MEDINT',  'e0000000-0000-0000-0000-000000000001', 'Médecine interne et polyvalente'),
 ('GERIA',   'e0000000-0000-0000-0000-000000000001', 'Gériatrie'),
 ('NEPHRO',  'e0000000-0000-0000-0000-000000000001', 'Néphrologie'),
 ('PNEUMO',  'e0000000-0000-0000-0000-000000000001', 'Pneumologie'),
 ('HEMATO',  'e0000000-0000-0000-0000-000000000001', 'Hématologie'),
 ('CHIR',    'e0000000-0000-0000-0000-000000000001', 'Chirurgie'),
 ('SSR',     'e0000000-0000-0000-0000-000000000002', 'Soins médicaux et de réadaptation'),
 ('MPR',     'e0000000-0000-0000-0000-000000000002', 'Médecine physique et de réadaptation');

alter table unite_fonctionnelle add column service_code varchar(20) references service (code);

update unite_fonctionnelle set service_code = 'SSR'    where code = '3000';
update unite_fonctionnelle set service_code = 'CHIR'   where code = '3801';
update unite_fonctionnelle set service_code = 'CARDIO' where code = '2100';
update unite_fonctionnelle set service_code = 'MPR'    where code = '4200';

insert into unite_fonctionnelle (code, libelle, telephone, fax, service_code) values
 ('0100', 'Urgences adultes',                     '04 00 00 01 00', null,             'URG'),
 ('2101', 'Cardiologie – hospitalisation',         '04 00 00 21 01', '04 00 00 21 98', 'CARDIO'),
 ('1100', 'Endocrinologie – diabétologie',         '04 00 00 11 00', '04 00 00 11 99', 'ENDOC'),
 ('1300', 'Maladies infectieuses et tropicales',   '04 00 00 13 00', null,             'INFECT'),
 ('1200', 'Médecine interne',                      '04 00 00 12 00', null,             'MEDINT'),
 ('1250', 'Médecine polyvalente',                  '04 00 00 12 50', null,             'MEDINT'),
 ('1700', 'Gériatrie aiguë',                       '04 00 00 17 00', null,             'GERIA'),
 ('1400', 'Néphrologie – consultations',           '04 00 00 14 00', null,             'NEPHRO'),
 ('1600', 'Pneumologie',                           '04 00 00 16 00', null,             'PNEUMO'),
 ('1500', 'Hématologie – hôpital de jour',         '04 00 00 15 00', null,             'HEMATO'),
 ('3500', 'Chirurgie viscérale et digestive',      '04 00 00 35 00', '04 00 00 35 99', 'CHIR');

alter table unite_fonctionnelle alter column service_code set not null;

-- Chaque séjour existant reçoit l'UF de son service (texte libre des migrations précédentes)
update sejour set uf_medicale_code = case
        when service = 'Cardiologie' and type = 'HOSPITALISATION_COMPLETE' then '2101'
        when service = 'Cardiologie' then '2100'
        when service = 'Endocrinologie' then '1100'
        when service = 'Maladies infectieuses' then '1300'
        when service = 'Médecine interne' then '1200'
        when service = 'Médecine polyvalente' then '1250'
        when service = 'Gériatrie' then '1700'
        when service = 'Néphrologie' then '1400'
        when service = 'Pneumologie' then '1600'
        when service = 'Hématologie' then '1500'
        when service = 'Chirurgie viscérale' then '3500'
        when service = 'Urgences adultes' then '0100'
        when service = 'SSR gériatrique' then '3000'
        when service = 'Médecine physique et de réadaptation' then '4200'
    end
where uf_medicale_code is null;
update sejour set uf_hebergement_code = uf_medicale_code where uf_hebergement_code is null;

-- --- Contexte du document -------------------------------------------------------------------------
-- Le séjour et l'UF sont figés à la création du compte rendu (le séjour appartient au patient du document).
alter table document
    add column sejour_id uuid references sejour (id),
    add column uf_code   varchar(10) references unite_fonctionnelle (code);
create index idx_document_sejour on document (sejour_id);
