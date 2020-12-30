const cheerio = require('cheerio');
const fs = require('fs');
const fsP = fs.promises;

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
    const prefix = '#----';

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
        wikiwalk.find('a').each((idx, elem) => {
            if ($(elem).attr('href') && $(elem).attr('href').startsWith(prefix)) {
                var name = $(elem).attr('href').substr(prefix.length);
                if (name.localeCompare(entry.name, 'en', { numeric: true}) < 0 && null != entry.prevName) {
                    var path = '../' + entry.prevName + '/' + IndexFile;
                    logger.debug(`Updating ${entry.name} prev link to ${path}`);
                    $(elem).attr('href', path);
                    $(elem).text(entry.prevName.toUpperCase());
                    updated = true;
                }
                else if (name.localeCompare(entry.name, 'en', { numeric: true}) > 0 && null != entry.nextName) {
                    var path = '../' + entry.nextName + '/'+ IndexFile;
                    logger.debug(`Updating ${entry.name} next link to ${path}`);
                    $(elem).text(entry.nextName.toUpperCase());
                    $(elem).attr('href', path);
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
