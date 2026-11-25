ALTER TABLE course_cover_reviews
    ALTER COLUMN image_url DROP NOT NULL;

ALTER TABLE course_cover_reviews
    ALTER COLUMN image_public_id DROP NOT NULL;

ALTER TABLE course_cover_reviews
    ADD CONSTRAINT course_cover_reviews_cover_pair_check
        CHECK ((image_url IS NULL) = (image_public_id IS NULL));
