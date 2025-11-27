drop table if exists track cascade ;
drop table if exists playlist cascade ;

Create TABLE track (
    id serial,
    title VARCHAR(200),
    raw_title VARCHAR(200),
    artist VARCHAR(200),
    video_id VARCHAR(20) unique not null,
    constraint PK_track PRIMARY KEY (video_id)
);

CREATE TABLE playlist (
    playlist_id SERIAL,
    video_id VARCHAR(20) UNIQUE NOT NULL,
    cmpt int,
    totalWatchTime time,
    constraint Pk_playlist PRIMARY KEY (playlist_id, video_id),
    constraint FK_playlist_track FOREIGN KEY (video_id)
        REFERENCES track (video_id) on update CASCADE
                                    ON DELETE CASCADE
);