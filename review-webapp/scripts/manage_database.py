"""Create the local app database or gracefully stop this project's MySQL."""
from pathlib import Path
import argparse
import os
import re
import pymysql
from local_config import load_env

ROOT = Path(__file__).resolve().parents[1]

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--shutdown", action="store_true")
    args = parser.parse_args()
    load_env(ROOT)
    marker = ROOT / ".local" / "database-configured"
    database = os.environ["DB_NAME"]
    account = os.environ["DB_USER"]
    if not re.fullmatch(r"[a-zA-Z0-9_]+", database) or not re.fullmatch(r"[a-zA-Z0-9_]+", account):
        raise ValueError("Database and account names must be simple identifiers")
    cfg = dict(host="127.0.0.1", port=int(os.environ["DB_PORT"]), user="root", charset="utf8mb4", autocommit=True)
    root_password = os.environ["MYSQL_ROOT_PASSWORD"]
    try:
        connection = pymysql.connect(**cfg, password=root_password)
    except pymysql.err.OperationalError as e:
        if e.args[0] != 1045 or marker.exists() or args.shutdown:
            raise
        connection = pymysql.connect(**cfg, password="")
    with connection:
        with connection.cursor() as cursor:
            if args.shutdown:
                cursor.execute("SHUTDOWN")
                print("Project MySQL stopped")
                return
            cursor.execute("ALTER USER 'root'@'localhost' IDENTIFIED BY %s", (root_password,))
            cursor.execute(f"CREATE DATABASE IF NOT EXISTS `{database}` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci")
            cursor.execute(f"CREATE USER IF NOT EXISTS '{account}'@'localhost' IDENTIFIED BY %s", (os.environ["DB_PASSWORD"],))
            cursor.execute(f"GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX, REFERENCES ON `{database}`.* TO '{account}'@'localhost'")
            cursor.execute(f"USE `{database}`")
            schema = ROOT / "backend" / "src" / "main" / "resources" / "schema.sql"
            for statement in schema.read_text(encoding="utf-8").split(";"):
                if statement.strip(): cursor.execute(statement)
    marker.write_text("Database initialized. Credentials are in .env.\n", encoding="utf-8")
    print(f"Database {database} is ready")

if __name__ == "__main__":
    main()
