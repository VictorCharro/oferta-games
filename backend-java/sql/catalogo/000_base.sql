--
-- PostgreSQL database dump
--

-- Dumped from database version 17.6
-- Dumped by pg_dump version 17.11 (Debian 17.11-1.pgdg13+2)

SET statement_timeout = 0;
SET lock_timeout = 0;
SET idle_in_transaction_session_timeout = 0;
SET transaction_timeout = 0;
SET client_encoding = 'UTF8';
SET standard_conforming_strings = on;
SELECT pg_catalog.set_config('search_path', '', false);
SET check_function_bodies = false;
SET xmloption = content;
SET client_min_messages = warning;
SET row_security = off;

SET default_tablespace = '';

SET default_table_access_method = heap;

--
-- Name: game_achievements; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.game_achievements (
    id bigint NOT NULL,
    game_id bigint NOT NULL,
    api_name text NOT NULL,
    display_name text,
    description text,
    icon_url text,
    global_percent numeric,
    "position" integer DEFAULT 0 NOT NULL
);


--
-- Name: game_achievements_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.game_achievements_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: game_achievements_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.game_achievements_id_seq OWNED BY public.game_achievements.id;


--
-- Name: game_details; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.game_details (
    game_id bigint NOT NULL,
    short_description text,
    genres text[],
    developers text[],
    publishers text[],
    release_date text,
    screenshots text[],
    review_score_desc text,
    review_positive integer,
    review_negative integer,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    trailer_url text,
    trailer_thumbnail text,
    about_full text,
    feature_highlights jsonb,
    categories text[],
    requirements_min text,
    requirements_rec text,
    dlc_steam_app_ids integer[]
);


--
-- Name: games; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.games (
    id bigint NOT NULL,
    itad_id uuid,
    title text NOT NULL,
    slug text NOT NULL,
    cover_url text,
    created_at timestamp with time zone DEFAULT now(),
    rank integer,
    is_dlc boolean,
    last_price_sync_at timestamp with time zone,
    last_steam_sync_at timestamp with time zone,
    steam_app_id integer,
    instant_gaming_url text,
    last_instant_gaming_sync_at timestamp with time zone,
    achievements_checked_at timestamp with time zone,
    last_manual_refresh_at timestamp with time zone
);


--
-- Name: games_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.games_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: games_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.games_id_seq OWNED BY public.games.id;


--
-- Name: instant_gaming_catalog; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.instant_gaming_catalog (
    product_id integer NOT NULL,
    title text NOT NULL,
    url text NOT NULL,
    discovered_at timestamp with time zone DEFAULT now() NOT NULL,
    normalized_title text DEFAULT ''::text NOT NULL
);


--
-- Name: offers; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.offers (
    id bigint NOT NULL,
    game_id bigint NOT NULL,
    source text NOT NULL,
    store_name text NOT NULL,
    price numeric(10,2) NOT NULL,
    regular_price numeric(10,2),
    currency text DEFAULT 'BRL'::text NOT NULL,
    url text NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    voucher_code text
);


--
-- Name: offers_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.offers_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: offers_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.offers_id_seq OWNED BY public.offers.id;


--
-- Name: price_history; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.price_history (
    id bigint NOT NULL,
    game_id bigint NOT NULL,
    price numeric(10,2) NOT NULL,
    captured_at timestamp with time zone DEFAULT now() NOT NULL,
    store_name text
);


--
-- Name: price_history_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.price_history_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: price_history_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.price_history_id_seq OWNED BY public.price_history.id;


--
-- Name: game_achievements id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.game_achievements ALTER COLUMN id SET DEFAULT nextval('public.game_achievements_id_seq'::regclass);


--
-- Name: games id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.games ALTER COLUMN id SET DEFAULT nextval('public.games_id_seq'::regclass);


--
-- Name: offers id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.offers ALTER COLUMN id SET DEFAULT nextval('public.offers_id_seq'::regclass);


--
-- Name: price_history id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.price_history ALTER COLUMN id SET DEFAULT nextval('public.price_history_id_seq'::regclass);


--
-- Name: game_achievements game_achievements_game_id_api_name_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.game_achievements
    ADD CONSTRAINT game_achievements_game_id_api_name_key UNIQUE (game_id, api_name);


--
-- Name: game_achievements game_achievements_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.game_achievements
    ADD CONSTRAINT game_achievements_pkey PRIMARY KEY (id);


--
-- Name: game_details game_details_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.game_details
    ADD CONSTRAINT game_details_pkey PRIMARY KEY (game_id);


--
-- Name: games games_itad_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.games
    ADD CONSTRAINT games_itad_id_key UNIQUE (itad_id);


--
-- Name: games games_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.games
    ADD CONSTRAINT games_pkey PRIMARY KEY (id);


--
-- Name: games games_slug_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.games
    ADD CONSTRAINT games_slug_key UNIQUE (slug);


--
-- Name: instant_gaming_catalog instant_gaming_catalog_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.instant_gaming_catalog
    ADD CONSTRAINT instant_gaming_catalog_pkey PRIMARY KEY (product_id);


--
-- Name: offers offers_game_id_source_store_name_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.offers
    ADD CONSTRAINT offers_game_id_source_store_name_key UNIQUE (game_id, source, store_name);


--
-- Name: offers offers_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.offers
    ADD CONSTRAINT offers_pkey PRIMARY KEY (id);


--
-- Name: price_history price_history_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.price_history
    ADD CONSTRAINT price_history_pkey PRIMARY KEY (id);


--
-- Name: idx_game_details_dlc_steam_app_ids; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_game_details_dlc_steam_app_ids ON public.game_details USING gin (dlc_steam_app_ids);


--
-- Name: idx_games_price_sync_general; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_games_price_sync_general ON public.games USING btree (last_price_sync_at NULLS FIRST, rank, id) WHERE ((itad_id IS NOT NULL) AND ((rank IS NULL) OR (rank > 2000)));


--
-- Name: idx_games_price_sync_relevant; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_games_price_sync_relevant ON public.games USING btree (last_price_sync_at NULLS FIRST, rank, id) WHERE ((itad_id IS NOT NULL) AND (rank IS NOT NULL) AND (rank <= 2000));


--
-- Name: idx_games_rank; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_games_rank ON public.games USING btree (rank);


--
-- Name: idx_games_steam_app_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_games_steam_app_id ON public.games USING btree (steam_app_id) WHERE (steam_app_id IS NOT NULL);


--
-- Name: idx_games_steam_sync; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_games_steam_sync ON public.games USING btree (last_steam_sync_at NULLS FIRST, id) WHERE ((is_dlc IS NULL) OR (cover_url IS NULL));


--
-- Name: idx_instant_gaming_catalog_normalized_title; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_instant_gaming_catalog_normalized_title ON public.instant_gaming_catalog USING btree (normalized_title);


--
-- Name: idx_offers_game_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_offers_game_id ON public.offers USING btree (game_id);


--
-- Name: idx_price_history_game_captured; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_price_history_game_captured ON public.price_history USING btree (game_id, captured_at DESC);


--
-- Name: game_achievements game_achievements_game_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.game_achievements
    ADD CONSTRAINT game_achievements_game_id_fkey FOREIGN KEY (game_id) REFERENCES public.games(id) ON DELETE CASCADE;


--
-- Name: game_details game_details_game_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.game_details
    ADD CONSTRAINT game_details_game_id_fkey FOREIGN KEY (game_id) REFERENCES public.games(id) ON DELETE CASCADE;


--
-- Name: offers offers_game_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.offers
    ADD CONSTRAINT offers_game_id_fkey FOREIGN KEY (game_id) REFERENCES public.games(id);


--
-- Name: price_history price_history_game_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.price_history
    ADD CONSTRAINT price_history_game_id_fkey FOREIGN KEY (game_id) REFERENCES public.games(id) ON DELETE CASCADE;


--
-- PostgreSQL database dump complete
--

