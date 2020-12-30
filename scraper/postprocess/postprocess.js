const fs = require('fs');
const fsP = fs.promises;
const he = require('he');
const path = require('path');
const cheerio = require('cheerio');
const piscina = require('piscina');

const config = require('../config');
const logger = require('../logger');
const utils = require('../utils');

// Handle uncaught exceptions / rejections
process.on('unhandledRejection', (reason, promise) => {
	logger.error('Unhandled Rejection at:', promise, 'reason:', reason);
});

process.on('uncaughtException', function (err) {
	logger.error('Unhandled Exception', err);
})

// Initialize worker thread pool
const pool = new piscina({
	minThreads: config.threadPoolSize,
	maxThreads: config.threadPoolSize
});

// Index filename
const indexFile = 'index.html';

// Reg exp to filter by scp-series and html files
const regExpScpSeriesPath = new RegExp('scp-series(?:$|-\\d+$)', 'ig');
const regExpScpTalesPath = new RegExp('scp-series-\\d+-tales-edition$', 'ig');
const regExpHtml = new RegExp('\.html$', 'ig');

/**
 * Process specific scripts that need to be fixed up
 * 
 * @param {string} inputPath - input path
 */
async function processScripts(inputPath) {
	const fixup = [
		{
			script: 'scp-wiki.wdfiles.com/common--javascript/html-block-iframe.js',
			replace: [
				{
					src: 'var iframe_hash = url_array[5];',
					dst: 'var iframe_hash = url_array[6];'
				}, {
					src: 'var resize_url = url_array[0] + \'//\' + url_array[6] + \'/common--javascript/resize-iframe.html\';',
					dst: 'var resize_url = url_array[0] + \'//\' + url_array[2] + \'/\' + url_array[7] + \'/common--javascript/resize-iframe.html\';'
				}
			]
		}, {
			script: 'www.scpwiki.com/common--javascript/resize-iframe.html',
			replace: [
				{
					src: 'parent.parent.$j(\'iframe.html-block-iframe[src$="/\' + id + \'"]\').height(height + \'px\');',
					dst: 'parent.parent.$j(\'iframe.html-block-iframe[src*="/\' + id + \'"]\').height(height + \'px\');'
				}
			]
		}
	]

	for (var i = 0; i < fixup.length; i++) {
		const entry = fixup[i];
		const filePath = path.join(inputPath, entry.script);
		logger.info(`Processing script '${filePath}'`);

		var body = await fsP.readFile(filePath, 'utf8');

		entry.replace.forEach(replace => {
			if (!body.includes(replace.src)) {
				logger.warn(`File '${filePath}' does not contain line: ${replace.src}`);
			} else {
				body = body.replace(replace.src, replace.dst);
			}
		});

		await fsP.writeFile(filePath, body);
	}
}

/**
 * Fixes up scp prev / next links
 * 
 * @param {array} scpList - array of scp urls
 */
async function processLinks(scpList) {
	scpList.sort((a, b) => a.name.localeCompare(b.name, 'en', { numeric: true }));

	for (i = 0; i < scpList.length; i++) {
		scpList[i].nextName = i + 1 < scpList.length ? scpList[i + 1].name : null;
		scpList[i].prevName = i - 1 >= 0 ? scpList[i - 1].name : null;
	}

	let work = scpList.map(async (entry) => {
		await pool.runTask({ level: logger.level, entry: entry }, __dirname + '/postprocess-worker-update-links.js');
	});

	await Promise.all(work);
}

/**
 * Removes empty entries from scp-series pages and generates list of scp's
 * 
 * @param {string} seriesFilePath - path to series file
 * @param {string} seriesName - series name
 */
async function processSeriesFile(seriesFilePath, seriesName) {
	logger.info(`Processing file '${seriesFilePath}', series '${seriesName}`);

	const body = await fsP.readFile(seriesFilePath);
	const $ = cheerio.load(body, {
		decodeEntities: false,
		lowerCaseAttributeNames: false,
	});

	const scpList = [];

	$('div#page-content li').each((idx, elem) => {
		var href = $(elem).find('a')

		if (href.attr('href') && href.attr('href').match(/scp-\d+/)) {
			href = href.first();
			if (href.hasClass('newpage')) {
				$(elem).remove();
			} else {
				const link = href.attr('href');
				const name = link.match(/scp-\d+/g)[0];
				const filePath = path.join(path.dirname(seriesFilePath), link);
				const e = $(elem).clone();
				e.find('a').each((i2, e2) => {
					$(e2).replaceWith($(e2).text());
				});
				const url = link.replace(new RegExp('^\.\.'), config.baseDir).replace('/index.html', '')
				const title = e.html();
				scpList.push({ name: name, filePath: filePath, url: url, title: title, series: seriesName });
			}
		}
	});

	await fsP.writeFile(seriesFilePath, $.html());
	return scpList;
}

async function generateScpList(inputPath, processedContent, scpList) {
	const filePath = path.join(inputPath, 'scplist.json');
	logger.info(`Saving SCP list to '${filePath}'`);

	const output = [];
	scpList.forEach(e => {
		entry = { name: e.name, url: e.url, title: e.title, series: e.series }

		if (processedContent.hasOwnProperty(e.url)) {
			rating = processedContent[e.url].rating
			if (rating) {
				entry.rating = rating
			}
		}

		output.push(entry);
	});
	await fsP.writeFile(filePath, JSON.stringify(output, null, 2));
}

/**
 * Processes series tales
 * 
 * @param {string} inputPath - input path
 */
async function processTales(inputPath) {
	logger.info('Processing tales');

	const basePath = path.join(inputPath, config.baseDir);
	if (!fs.existsSync(basePath)) {
		throw new Error(`Path ${basePath} not found`);
	}

	var files = await fsP.readdir(basePath);
	let talesDirs = files.filter((elem) => { return elem.match(regExpScpTalesPath); });

	const filePath = path.join(inputPath, 'tales.json');
	logger.info(`Saving tales to '${filePath}'`);

	const output = { talesnum : talesDirs.length };
	await fsP.writeFile(filePath, JSON.stringify(output, null, 2));
}

/**
 * Processes scp series pages
 * 
 * @param {string} inputPath - input path
 */
async function processSeries(inputPath, processedContent) {
	logger.info('Processing links');

	const basePath = path.join(inputPath, config.baseDir);
	if (!fs.existsSync(basePath)) {
		throw new Error(`Path ${basePath} not found`);
	}

	var files = await fsP.readdir(basePath);
	let scpDirs = files.filter((elem) => { return elem.match(regExpScpSeriesPath); });

	var scpList = [];
	for (var i = 0; i < scpDirs.length; i++) {
		var filePath = path.join(basePath, scpDirs[i], indexFile);
		scpList = scpList.concat(await processSeriesFile(filePath, scpDirs[i]));
	}

	await generateScpList(inputPath, processedContent, scpList);
	await processLinks(scpList);
}

/**
 * Produces menu json file
 * 
 * @param {string} inputPath - input path
 */
async function generateMenu(inputPath) {
	const filePath = path.join(inputPath, 'menu.json');
	logger.info(`Saving menu to '${filePath}'`);

	const indexHtmlFile = path.join(path.join(inputPath, config.baseDir), indexFile);

	const body = await fsP.readFile(indexHtmlFile);
	const $ = cheerio.load(body, {
		decodeEntities: false,
		lowerCaseAttributeNames: false,
	});

	const menu = [];
	$('div#top-bar > div.top-bar > ul').children((idx, elem) => {
		const section = { heading: $(elem).contents().first().text().trim() };
		const items = [];
		$(elem).find('li a[href]').each((idx2, entry) => {
			const name = $(entry).text().trim();
			var url = $(entry).attr('href');
			if (!url.startsWith('/')) {
				url = config.baseDir + '/' + url;
			}
			items.push({ name: he.decode(name), url: url });
		});

		section.items = items;
		menu.push(section);
	});

	if (menu.length > 0) {
		await fsP.writeFile(filePath, JSON.stringify(menu, null, 2));
	}
}

/**
 * Produces tales list
 * 
 * @param {string} inputPath - input path
 */
async function generateTaleList(inputPath, processedContent) {
	const filePath = path.join(inputPath, 'talelist.json');

	var taleList = []
	for (const key in processedContent) {
		const entry = processedContent[key]
		if (entry.title && entry.tags && entry.tags.includes('tale')) {
			const taleEntry = {'title' : entry.title, 'url' : entry.url}
			const rating = entry.rating
			if (rating) {
				taleEntry.rating = rating
			}

			taleList.push(taleEntry)
		}
	}

	if (taleList.length > 0) {
		logger.info(`Saving tale list (${taleList.length} entries) to '${filePath}'`);
		await fsP.writeFile(filePath, JSON.stringify(taleList, null, 2));
	}
}

/**
 * Produces rating list
 * 
 * @param {string} inputPath - input path
 */
async function generateRatingList(inputPath, processedContent) {
	const filePath = path.join(inputPath, 'ratinglist.json');

	var ratingList = {}
	for (const key in processedContent) {
		const entry = processedContent[key]
		if (entry.rating) {
			ratingList[key] = entry.rating
		}
	}

	logger.info(`Saving rating list (${ratingList.length} entries) to '${filePath}'`);
	await fsP.writeFile(filePath, JSON.stringify(ratingList, null, 2));
}

/**
 * Cleanup content and inject CSS callback into html files
 * 
 * @param {string} inputPath - input path
 */
async function processContent(inputPath) {
	logger.info('Processing content');

	const processedContent = {}

	const basePath = path.join(inputPath, config.baseDir);
	if (!fs.existsSync(basePath)) {
		throw new Error(`Path ${basePath} not found`);
	}

	var files = await utils.findInDir(basePath, regExpHtml);
	let work = files.map(async (filePath) => {
		const indexHtmlFile = path.join(path.join(inputPath, config.baseDir), indexFile);
		if (indexHtmlFile.toLocaleLowerCase() != filePath.toLocaleLowerCase()) {
			const result = await pool.runTask({ level: logger.level, filePath: filePath }, __dirname + '/postprocess-worker-process-content.js');

			if (result) {
				const url = config.baseDir + '/' + path.dirname(filePath).substring(basePath.length+1).replace(/\\/g, '/')
				result.url = url
				processedContent[url] = result
			}
		}
	});

	await Promise.all(work);
	return processedContent
}

/**
 * Post process: 1) Fix up scripts, 2) Fix up scp list & prev / next links, 3) Cleanup content and inject CSS callbacks
 * 
 * @param {string} inputPath - input path
 */
async function postProcess(inputPath) {
	//await generateMenu(inputPath);
	await processTales(inputPath);
	await processScripts(inputPath);
	const processedContent = await processContent(inputPath);
	await generateTaleList(inputPath, processedContent);
	await processSeries(inputPath, processedContent);
	await generateRatingList(inputPath, processedContent)
}

/**
 * Main entry point
 * 
 * @param {*} argv - args
 */
function main(argv) {
	logger.level = argv.log ?? 'info';

	// Launch post processor
	logger.info(`Post-processing '${argv.input}'`);

	(async () => {
		try {
			await postProcess(argv.input);
			logger.info('******************* DONE *****************');
			process.exit(0);
		} catch (err) {
			logger.error('******************* ERROR *****************');
			logger.error('Error', err);
			process.exit(1);
		}
	})();
}

module.exports = main