ALTER TABLE courses
    ADD COLUMN image_url VARCHAR(1024);

ALTER TABLE courses
    ADD COLUMN image_public_id VARCHAR(255);

ALTER TABLE courses
    ADD CONSTRAINT courses_cover_pair_check
    CHECK ((image_url IS NULL) = (image_public_id IS NULL));
