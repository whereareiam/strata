-- Runs on every database.
CREATE TABLE accounts (
    id INT PRIMARY KEY,
    name VARCHAR(32) NOT NULL
);

INSERT INTO accounts (id, name) VALUES (1, 'semi;colon');
