const cheerio = require('cheerio');
const fs = require('fs');
const fsP = fs.promises;

const config = require('../config');
const logger = require('../logger');

/**
 * Fixes up prev / next link in document
 * 
 * @param {object} args - args
 */
async function updateLinks(args) {
    logger.level = args.level;
    const entry = args.entry;

    const IndexFile = 'index.html';

    logger.debug(`Updating links: '${entry.filePath}'`);

    var body = null;
    try {
        body = await fsP.readFile(entry.filePath);
    } catch (err) {
        if (err.code === 'ENOENT') {
            logger.warn(`File not found: '${entry.filePath}', skipping`);
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

    var wikiwalk = $('div.footer-wikiwalk-nav div p');
    if (wikiwalk.length > 0) {
        // if newpage class, then update. otherwise, it's correct.
        wikiwalk.find('a.newpage[href]').each((idx, elem) => {
            var name = null;
            const matches = $(elem).attr('href').match(/(scp-\d+)/g);
            if (matches != null) {
                name = matches[0];
            }

            if (name) {
                if (name.localeCompare(entry.name, 'en', { numeric: true }) < 0 && null != entry.prevName) {
                    var path = entry.prevUrl.replace(config.baseDir, '..') + '/' + IndexFile;
                    logger.info(`Updating ${entry.name} prev link to ${path}`);
                    $(elem).attr('href', path);
                    $(elem).text(entry.prevName.toUpperCase());
                    $(elem).removeClass("newpage");
                    if (!$(elem).attr('class').length) $(elem).removeAttr('class');
                    updated = true;
                }
                else if (name.localeCompare(entry.name, 'en', { numeric: true }) > 0 && null != entry.nextName) {
                    var path = entry.nextUrl.replace(config.baseDir, '..') + '/' + IndexFile;
                    logger.info(`Updating ${entry.name} next link to ${path}`);
                    $(elem).text(entry.nextName.toUpperCase());
                    $(elem).attr('href', path);
                    $(elem).removeClass("newpage");
                    if (!$(elem).attr('class').length) $(elem).removeAttr('class');
                    updated = true;
                }
            }
        });
    }

    if (updated) {
        await fsP.writeFile(entry.filePath, $.html());
    }
}

module.exports = updateLinks;
