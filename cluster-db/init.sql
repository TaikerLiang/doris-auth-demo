-- One MariaDB for the PoC, one schema per fab. In real deployments every fab has its own Cluster DB.
CREATE DATABASE cluster_fab_a;
CREATE DATABASE cluster_fab_b;
CREATE USER 'cluster'@'%' IDENTIFIED BY 'cluster';
GRANT ALL ON cluster_fab_a.* TO 'cluster'@'%';
GRANT ALL ON cluster_fab_b.* TO 'cluster'@'%';
