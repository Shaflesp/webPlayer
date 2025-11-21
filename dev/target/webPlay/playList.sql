drop table if exists playlist;
CREATE TABLE playlist (
    id SERIAL PRIMARY KEY,
    title VARCHAR(100),
    artist VARCHAR(100),
    video_id VARCHAR(20) UNIQUE NOT NULL
);