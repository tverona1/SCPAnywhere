const cheerio = require('cheerio');
const fs = require('fs');
const fsP = fs.promises;
const path = require('path');

const config = require('../config');
const logger = require('../logger');

const IndexFile = 'index.html';

/**
 * Update link on element
 * 
 * @param {*} $ - cheerio document instance
 * @param {*} elem - element to update
 * @param {*} entry - entry corresponding to element
 * @param {string} inputPath - input path
 * @param {string} filePath - element filepath
 * @param {boolean} isPrevious - whether updating previous or next entry
 */
function updateLink($, elem, entry, inputPath, filePath, isPrevious) {
    const urlToUpdate = isPrevious ? entry.prevUrl : entry.nextUrl;
    const nameToUpdate = isPrevious ? entry.prevName : entry.nextName;
    const elemHref = $(elem).attr('href');

    // Relative urls are already correct since they have already resolved. Only process non-relative urls
    if (!elemHref.includes('..')) {
        // Get target url relative to file path
        const normalizedFilePath = filePath.replace(/\\index.html$/,'').replace(/\\/g, '/');
        const urlAsFilePath = (inputPath + '/' + urlToUpdate).replace(/\\/g, '/');
        const url = (path.relative(normalizedFilePath, urlAsFilePath) + '/' + IndexFile).replace(/\\/g, '/');

        logger.info(`Updating ${entry.name} ${isPrevious ? 'prev' : 'next'} link to ${url}`);
        $(elem).attr('href', url);
    }

    // Set name
    $(elem).text(nameToUpdate.toUpperCase());

    // Remove newpage class
    $(elem).removeClass("newpage");
    if (!$(elem).attr('class').length) $(elem).removeAttr('class');
}

/**
 * Fixes up prev / next link in document
 * 
 * @param {object} args - args
 */
async function updateLinks(args) {
    logger.level = args.level;
    const inputPath = args.inputPath;
    const filePath = args.filePath;
    const entry = args.entry;

    logger.debug(`Updating links: '${filePath}'`);

    var body = null;
    try {
        body = await fsP.readFile(filePath);
    } catch (err) {
        if (err.code === 'ENOENT') {
            logger.warn(`File not found: '${filePath}', skipping`);
            return;
        } else {
            throw err;
        }
    }

    const $ = cheerio.load(body, {
        decodeEntities: false,
        lowerCaseAttributeNames: false,
    });

    var updated = false;

    var wikiwalk = $('div.footer-wikiwalk-nav p');
    if (wikiwalk.length > 0) {
        // if newpage class, then update. otherwise, it's correct.
        wikiwalk.find('a.newpage[href]').each((idx, elem) => {
            var name = null;
            const matches = $(elem).attr('href').match(/(scp-\d+)/g);
            if (matches != null &&  matches.length > 0) {
                name = matches[0];
            }

            if (name) {
                if (name.localeCompare(entry.name, 'en', { numeric: true }) < 0 && null != entry.prevName) {
                    updateLink($, elem, entry, inputPath, filePath, true);
                    updated = true;
                }
                else if (name.localeCompare(entry.name, 'en', { numeric: true }) > 0 && null != entry.nextName) {
                    updateLink($, elem, entry, inputPath, filePath, false);
                    updated = true;
                }
            }
        });
    }

    if (updated) {
        await fsP.writeFile(filePath, $.html());
    }
}

module.exports = updateLinks;
