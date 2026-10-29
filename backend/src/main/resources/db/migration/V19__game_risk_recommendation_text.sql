-- The deposit / compensation recommendation of a risk is free text written by staff
-- (e.g. "Nên bằng 30–50% giá trị account"), not a number. Existing amounts become text.
ALTER TABLE game_risks ADD COLUMN recommendation text CHECK (recommendation IS NULL OR length(recommendation) <= 500);
UPDATE game_risks
   SET recommendation = replace(to_char(recommended_amount, 'FM999,999,999,999'), ',', '.') || ' đ'
 WHERE recommended_amount > 0;
ALTER TABLE game_risks DROP COLUMN recommended_amount;
