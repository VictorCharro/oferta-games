import postgres from 'postgres';

const sql = postgres(process.env.DATABASE_URL!, {
  ssl: 'require',
  prepare: false, // PgBouncer transaction mode
});

export default sql;
