const { S3 } = require("../s3")
const fs = require('fs-extra');
const AdmZip = require('adm-zip');

const { UserManager } = require('./user');


const isAString = variable => typeof variable === 'string' || variable instanceof String;

const putWasmFileToS3 = (pluginId, wasmFolder) => {
  const { s3, Bucket } = S3.state()

  return new Promise((resolve, reject) => {
    fs.readFile(wasmFolder, (err, data) => {
      if (err)
        reject(err)
      else
        s3.upload({
          Bucket,
          Key: pluginId,
          Body: data
        }, err => err ? reject(err) : resolve())
    })
  })
}


const putBuildLogsToS3 = (logId, logsFolder) => {
  const { s3, Bucket } = S3.state()

  const zip = new AdmZip()
  zip.addLocalFolder(logsFolder, 'logs')

  return new Promise((resolve, reject) => {
    s3.upload({
      Bucket,
      Key: logId,
      Body: zip.toBuffer()
    }, err => err ? reject(err) : resolve())
  })
}

function putWasmInformationsToS3(userMail, pluginId, newHash, generateWasmName) {
  const userReq = {
    user: {
      email: userMail
    }
  }

  return UserManager.getUser(userReq)
    .then(data => UserManager.updateUser(userReq, {
      ...data,
      plugins: data.plugins.map(plugin => {
        if (plugin.pluginId !== pluginId) {
          return plugin;
        }

        let versions = plugin.versions || [];

        // convert legacy array
        if (versions.length > 0 && isAString(versions[0])) {
          versions = versions.map(name => ({ name }))
        }

        const index = versions.findIndex(item => item.name === generateWasmName);
        if (index === -1)
          versions.push({
            name: generateWasmName,
            updated_at: Date.now(),
            creator: userMail
          })
        else {
          versions[index] = {
            ...versions[index],
            updated_at: Date.now(),
            creator: userMail
          }
        }

        return {
          ...plugin,
          last_hash: newHash,
          wasm: generateWasmName,
          versions
        }
      })
    }))
}

module.exports = {
  putWasmFileToS3,
  putBuildLogsToS3,
  putWasmInformationsToS3
}