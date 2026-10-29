-- Staff roles: a user holding at least one staff role is a staff account and does not get the permissions of
-- non-staff roles (the customer role USER). Staff accounts operate the platform; they do not rent or list
-- accounts, place orders or withdraw money with the same login.
ALTER TABLE roles ADD COLUMN is_staff boolean NOT NULL DEFAULT true;
UPDATE roles SET is_staff = false WHERE code = 'USER';
