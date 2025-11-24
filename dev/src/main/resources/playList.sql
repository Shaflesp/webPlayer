drop table if exists playlist;
CREATE TABLE playlist (
    id SERIAL PRIMARY KEY,
    title VARCHAR(200),
    raw_title VARCHAR(200),
    artist VARCHAR(200),
    video_id VARCHAR(20) UNIQUE NOT NULL
);