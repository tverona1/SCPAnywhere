const fs = require('fs');
const fsP = fs.promises;
const path = require('path');
const piscina = require('piscina');
const util = require('util');
const exec = util.promisify(require('child_process').exec);

const logger = require('../logger');
const utils = require('../utils');
const config = require('../config');

// Initialize worker thread pool
const pool = new piscina({
	filename: __dirname + '/generate-zips-worker.js',
	minThreads: config.threadPoolSize,
	maxThreads: config.threadPoolSize
});

// Handle uncaught exceptions / rejections
process.on('unhandledRejection', (reason, promise) => {
	logger.error('Unhandled Rejection at:', promise, 'reason:', reason);
});

process.on('uncaughtException', function (err) {
	logger.error('Unhandled Exception', err);
})

// Base extension filter
const baseExtFilter = /\.(?:html|css|js|php)$/;

// Index filename
const indexFile = 'index.html';

// Tag rules define how to categorize files into zip files
const tagRules = [
	{
		name: 'audio',
		pathPattern: [
			new RegExp('\\.(?:mp3|wav|ac3|ogg|m4a)(?:\\?|$)', 'ig')
		]
	}, {
		name: 'video',
		pathPattern: [
			new RegExp('\\.(?:mp4|wmv|mov)(?:\\?|$)', 'ig')
		]
	}, {
		name: 'images_scp',
		tags: [
			'scp', 'supplement'
		],
		ignoreTagsFor: [
			utils.getUrlRegExp(config.baseDir, '/scp-series(?:-\\d+)?'),
			utils.getUrlRegExp(config.baseDir, '/scp-series-\\d+-tales-edition'),
			utils.getUrlRegExp(config.baseDir, '/scp-001'),
			new RegExp(utils.baseUrlRegEx + '(?:/$|$)', 'ig')
		]
	}, {
		name: 'images_tales',
		tags: [
			'tale'
		],
		ignoreTagsFor: [
			utils.getUrlRegExp(config.baseDir, '/foundation-tales'),
		]
	}, {
		name: 'images_other'
	}
]

//  Max file size in bytes (2GB)
const maxZipSize = 2000000000;

/**
 * Helper to pretty-print numbers with commas
 * @param {number} n - number to convert to string
 */
function formatNumber(n) {
	return String(n).replace(/(.)(?=(\d{3})+$)/g, '$1,')
}

/**
 * Retrieves all files in specified dir and categorizes them into base (i.e. html-related files) and media (i.e. png, mp3 etc).
 * 
 * @param {string} dir - path to process
 * @param {regexp} filter - regexp to filter whether file is media or not
 */
async function getAllFiles(dir, filter) {
	var baseFiles = [];
	var mediaFiles = [];
	var totalSize = 0;
	var baseSize = 0;
	var mediaSize = 0;

	await getAllFilesInternal(dir, filter);

	async function getAllFilesInternal(dir, filter) {
		const files = await fsP.readdir(dir);
		for (var i = 0; i < files.length; i++) {
			file = files[i];
			const filePath = path.join(dir, file);
			const fileStat = fs.lstatSync(filePath);

			if (fileStat.isDirectory()) {
				await getAllFilesInternal(filePath, filter);
			} else {
				var entry = { path: filePath, size: fileStat.size, isBase: filter.test(filePath), isProcessed: false };
				if (entry.isBase) {
					baseFiles.push(entry);
					baseSize += entry.size;
				} else {
					mediaFiles.push(entry);
					mediaSize += entry.size;
				}
				totalSize += entry.size;
			}
		}
	}

	return { baseFiles: baseFiles, mediaFiles: mediaFiles, totalSize: totalSize, baseSize: baseSize, mediaSize: mediaSize };
}

/**
 * Given a list of file entries, returns an array of OutputFolder objects
 * 
 * @param {array} fileList - array of {size, path} objects representing individual files & their sizes
 * @param {string} prefixName - folder prefix name
 * @param {bool} compressFlag - compression flag indicates whether the folder should be compressed
 */
function processFolder(fileList, prefixName, compressFlag = false) {
	class FileInfo {
		constructor(path) {
			this.path = path;
		}
	}

	class OutputFolder {
		constructor(prefix, index, compressFlag) {
			this.prefix = prefix;
			this.size = 0;
			this.index = index;
			this.files = [];
			this.compress = compressFlag;
		}

		getFolderName() {
			return this.prefix + '.' + this.index;
		}
	}

	const outputFolders = [];
	var index = 1;
	var outputFolder = new OutputFolder(prefixName, index, compressFlag);

	for (var i = 0; i < fileList.length; i++) {
		if (outputFolder.size + fileList[i].size > maxZipSize) {
			outputFolders.push(outputFolder);
			logger.info(`Processed output folder '${outputFolder.getFolderName()}', total size: ${formatNumber(outputFolder.size)}`);
			index++;
			outputFolder = new OutputFolder(prefixName, index, compressFlag);
		}

		outputFolder.files.push(new FileInfo(fileList[i].path));
		outputFolder.size += fileList[i].size;
	}

	if (outputFolder.size > 0) {
		outputFolders.push(outputFolder);
		logger.info(`Processed output folder '${outputFolder.getFolderName()}', total size: ${formatNumber(outputFolder.size)}`);
	}
	return outputFolders;
}

/**
 * Categorizees files into sets of folders based on tag rules.
 * 
 * @param {string} inputPath - input path
 */
async function categorize(inputPath) {
	const basePath = path.join(inputPath, config.baseDir);

	const files = await utils.findInDir(basePath, indexFile);
	logger.info(`Total html files to process: ${formatNumber(files.length)}`);

	const categories = {};
	for (var i = 0; i < tagRules.length; i++) {
		categories[tagRules[i].name] = { name: tagRules[i].name, paths: new Set() }
	}

	// Categorize by tag rules
	const work = files.map(async (filePath) => {
		const ret = await pool.runTask({ level: logger.level, filePath: filePath, tagRules: tagRules });
		if (ret) {
			for (var elem of ret.paths) {
				categories[ret.tag].paths.add(elem);
			}
		}
		else {
			logger.warn(`No tag found for ${filePath}`);
		}
	});

	await Promise.all(work);
	return categories;
}

/**
 * Helper function used to sort file entries
 * 
 * @param {*} a - entry
 * @param {*} b - entry
 */
function compareFileEntry(a, b) {
	return a.path.toLowerCase().localeCompare(b.path.toLowerCase());
}

/**
 * Processes a category into OutputFolder objects
 * 
 * @param {object} category - category object
 * @param {array} files - array of file entries
 */
function processCategory(category, files) {
	logger.info(`Processing category ${category.name}`);

	categoryFiles = [];

	for (var elem of category.paths) {
		var idx = utils.binarySearchSortedArray(files, { path: elem }, compareFileEntry);
		if (idx === -1) {
			continue;
		}

		if (!files[idx].isProcessed) {
			files[idx].isProcessed = true;
			categoryFiles.push(files[idx]);
		}
	}

	return processFolder(categoryFiles, category.name);
}

/**
 * Processes a set of categories to a set of output folder entries
 * 
 * @param {string} inputPath - input path
 * @param {array} categories - array of categories
 */
async function processCategories(inputPath, categories) {
	var files = await getAllFiles(inputPath, baseExtFilter);
	files.baseFiles.sort(compareFileEntry);
	files.mediaFiles.sort(compareFileEntry)

	logger.info(`Total files: ${formatNumber(files.baseFiles.length + files.mediaFiles.length)}, total size: ${formatNumber(files.totalSize)} bytes, base files: ${formatNumber(files.baseFiles.length)}, base size: ${formatNumber(files.baseSize)} bytes, media files: ${formatNumber(files.mediaFiles.length)}, media size: ${formatNumber(files.mediaSize)} bytes`);

	var mediaFolders = [];

	// Categorize by the tag rules
	for (var i = 0; i < files.mediaFiles.length; i++) {
		const mediaFile = files.mediaFiles[i];
		for (var j = 0; j < tagRules.length; j++) {
			const rule = tagRules[j];
			if (rule.pathPattern && (rule.pathPattern.findIndex(element => mediaFile.path.match(element)) !== -1)) {
				categories[rule.name].paths.add(mediaFile.path);
				break;
			}
		}
	}

	for (var i = 0; i < tagRules.length; i++) {
		const category = categories[tagRules[i].name];
		mediaFolders = mediaFolders.concat(processCategory(category, files.mediaFiles));
	}

	// Throw the rest of the stuff into base
	for (var i = 0; i < files.mediaFiles.length; i++) {
		const entry = files.mediaFiles[i];
		if (!entry.isProcessed) {
			logger.debug(`Entry not processed: ${entry.path}, size: ${entry.size}`);

			files.baseFiles.push(entry);
			files.baseSize += entry.size;
		}
	}

	const baseFolders = processFolder(files.baseFiles, 'base', true);
	return baseFolders.concat(mediaFolders);
}

/**
 * Copies input files to categorized output folder
 * 
 * @param {string} inputPath - input path
 * @param {string} outputPath - output path
 * @param {object} outputFolder - folder object
 */
async function copyToOutputFolder(inputPath, outputPath, outputFolder) {
	logger.info(`Copying ${formatNumber(outputFolder.files.length)} files (${formatNumber(outputFolder.size)} bytes) to ${outputFolder.getFolderName()}`);

	for (var i = 0; i < outputFolder.files.length; i++) {
		file = outputFolder.files[i];
		var outputFilePath = path.join(outputPath, outputFolder.getFolderName(), file.path.substr(inputPath.length));

		if (!fs.existsSync(path.dirname(outputFilePath))) {
			await fsP.mkdir(path.dirname(outputFilePath), { recursive: true });
		}

		await fsP.copyFile(file.path, outputFilePath);

		logger.debug(`Copied from ${file.path} to ${outputFilePath}`);
	}
}

/**
 * Copies input files to categorized output folders
 * 
 * @param {string} inputPath - input path
 * @param {string} outputPath - output path
 * @param {array} outputFolders - array of folder object
 */
async function copyToOutputFolders(inputPath, outputPath, outputFolders) {
	for (var i = 0; i < outputFolders.length; i++) {
		await copyToOutputFolder(inputPath, outputPath, outputFolders[i]);
	}
}

/**
 * Generates zip files by compressing folders in the specified path
 * 
 * @param {string} zipExe - path to zip exe (assumes 7z syntax)
 * @param {array} folders - folder objects
 */
async function generateZipFiles(zipExe, outputPath, folders) {

	for (var i = 0; i < folders.length; i++) {
		folders[i].files = null;

		const folderPath = path.join(outputPath, folders[i].getFolderName());
		const stat = fs.lstatSync(folderPath);

		if (stat.isDirectory()) {
			const zipFileName = folders[i].getFolderName() + '.zip';
			const zipFilePath = path.join(outputPath, zipFileName)
			logger.info(`Generating ${zipFileName}`);
			const cmd = `"${zipExe}" a "${zipFilePath}" "${path.join(folderPath, '*')}" -tzip ${folders[i].compress ? "":"-mx=0"}`;
			logger.debug(`Executing: ${cmd}`);

			try {
				const { stdout, stderr } = await exec(cmd);
			} catch (e) {
				logger.error(`Failed to execute ${zipExe}`, e);
				throw e;
			}
		}
	}
}

/**
 * Main entry point
 * 
 * @param {*} argv - args
 */
function main(argv) {
	logger.level = argv.log ?? 'info';

	(async () => {
		try {
			logger.info(`Processing input path '${argv.input}'`);

			const categories = await categorize(argv.input);
			const outputFolders = await processCategories(argv.input, categories);
			await copyToOutputFolders(argv.input, argv.output, outputFolders);
			await generateZipFiles(argv.zipExe, argv.output, outputFolders);
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