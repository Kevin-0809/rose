-- Expand field-difference tracking values for MERGE source rows longer than 2000 characters.
alter table ana_field_diff_tracking_export alter column orig_field_value type text;
alter table ana_field_diff_tracking_export alter column dest_field_value type text;
